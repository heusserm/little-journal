import AVFoundation
import ComposeApp
import Foundation
import Speech

/// The iOS half of the `Transcriber` interface declared in Kotlin.
///
/// This lives in Swift because it has to: `SpeechAnalyzer` is a Swift-only API
/// (an actor exposing AsyncSequence) and Kotlin/Native interops through
/// Objective-C headers, which never see it. Swift implements the protocol and
/// is injected into `MainViewController`.
///
/// Every callback is delivered on the main thread — Compose state is read there
/// and the audio tap runs on a realtime thread.
final class IosTranscriber: NSObject, Transcriber {

    var isAvailable: Bool { true }

    /// Never claim "this is the Simulator" without checking. The Simulator ships
    /// no ASR assets and never will; a device with the same symptom has a real,
    /// fixable problem, and conflating the two sends you down the wrong path.
    private var environmentNote: String {
        #if targetEnvironment(simulator)
        return "Simulator (no speech assets exist here)"
        #else
        return "This device"
        #endif
    }

    private let engine = AVAudioEngine()
    private var analyzer: SpeechAnalyzer?
    private var transcriber: SpeechTranscriber?
    private var continuation: AsyncStream<AnalyzerInput>.Continuation?
    private var resultsTask: Task<Void, Never>?
    private var startTask: Task<Void, Never>?

    // MARK: Transcriber

    func start(listener: TranscriberListener) {
        startTask = Task { await self.begin(listener) }
    }

    func stop() {
        startTask?.cancel()
        engine.stop()
        engine.inputNode.removeTap(onBus: 0)
        continuation?.finish()
        continuation = nil

        let analyzer = self.analyzer
        self.analyzer = nil
        Task {
            try? await analyzer?.finalizeAndFinishThroughEndOfInput()
        }
        resultsTask?.cancel()
        resultsTask = nil

        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    // MARK: internals

    private func onMain(_ block: @escaping () -> Void) {
        if Thread.isMainThread { block() } else { DispatchQueue.main.async(execute: block) }
    }

    private func begin(_ listener: TranscriberListener) async {
        do {
            onMain { listener.onStatus(message: "Asking for the microphone") }
            guard await AVAudioApplication.requestRecordPermission() else {
                onMain { listener.onError(message: "Microphone access denied. Enable it in Settings.") }
                return
            }

            // Microphone access is not enough. Speech recognition is a separate
            // permission, and without it the OS refuses to subscribe the app to
            // any ASR asset -- which surfaces as the misleading "not subscribed
            // to transcription.en".
            onMain { listener.onStatus(message: "Asking for speech recognition") }
            let speechAuth: SFSpeechRecognizerAuthorizationStatus = await withCheckedContinuation { cont in
                SFSpeechRecognizer.requestAuthorization { cont.resume(returning: $0) }
            }
            guard speechAuth == .authorized else {
                onMain {
                    listener.onError(message: "Speech recognition not authorised (\(speechAuth.rawValue)). Enable it in Settings > Little Journal.")
                }
                return
            }

            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.record, mode: .measurement, options: [.duckOthers])
            try session.setActive(true, options: .notifyOthersOnDeactivation)

            let wanted = Locale(identifier: "en-US")
            let supported = await SpeechTranscriber.supportedLocales
            let locale = await SpeechTranscriber.supportedLocale(equivalentTo: wanted) ?? wanted

            // An empty list is NOT a reason to stop. On a real device it can simply
            // mean nothing is installed yet, which is exactly the state a first run
            // is in -- bailing here would block the very download that fixes it.
            // Report it and carry on; let the asset install produce the real error.
            if supported.isEmpty {
                onMain { listener.onStatus(message: "\(self.environmentNote) reports 0 installed locales; trying anyway") }
            }

            let transcriber = SpeechTranscriber(locale: locale, preset: .progressiveTranscription)
            self.transcriber = transcriber

            // Reserve the locale so its asset is attributed to this app. Harmless
            // if the system has already installed the model.
            //
            // NOTE: none of this works in the iOS Simulator. The simulator ships
            // no speech assets -- the log says "GeneralASR is not supported on
            // this platform" and reports zero available languages -- so dictation
            // can only be exercised on a physical device.
            do {
                _ = try await AssetInventory.reserve(locale: locale)
            } catch {
                // Do not swallow this -- it is the step that decides whether the
                // asset check below can work at all.
                onMain { listener.onStatus(message: "Locale reserve failed: \(error.localizedDescription)") }
            }

            // Language models are downloaded on demand and managed by the OS,
            // not bundled with the app. First run pays this cost once.
            let status = await AssetInventory.status(forModules: [transcriber])
            if status != .installed {
                onMain { listener.onStatus(message: "Model status \(status) — downloading…") }
                if let request = try await AssetInventory.assetInstallationRequest(supporting: [transcriber]) {
                    try await request.downloadAndInstall()
                    onMain { listener.onStatus(message: "Model installed") }
                } else {
                    onMain {
                        listener.onError(message: "No installable model for \(locale.identifier). \(self.environmentNote).")
                    }
                    return
                }
            }

            guard let analyzerFormat = await SpeechAnalyzer.bestAvailableAudioFormat(compatibleWith: [transcriber]) else {
                onMain { listener.onError(message: "No compatible audio format on this device.") }
                return
            }

            let (stream, continuation) = AsyncStream<AnalyzerInput>.makeStream()
            self.continuation = continuation

            let analyzer = SpeechAnalyzer(modules: [transcriber])
            self.analyzer = analyzer
            try await analyzer.start(inputSequence: stream)

            resultsTask = Task { [weak self] in
                guard let self else { return }
                do {
                    for try await result in transcriber.results {
                        let piece = String(result.text.characters)
                        let isFinal = result.isFinal
                        self.onMain {
                            if isFinal {
                                listener.onFinal(text: piece)
                            } else {
                                listener.onPartial(text: piece)
                            }
                        }
                    }
                } catch {
                    self.onMain { listener.onError(message: error.localizedDescription) }
                }
            }

            try startEngine(convertingTo: analyzerFormat, into: continuation)
            onMain { listener.onStatus(message: "Listening") }
        } catch {
            onMain { listener.onError(message: error.localizedDescription) }
        }
    }

    /// Taps the microphone and feeds format-converted buffers to the analyzer.
    private func startEngine(
        convertingTo analyzerFormat: AVAudioFormat,
        into continuation: AsyncStream<AnalyzerInput>.Continuation
    ) throws {
        let input = engine.inputNode
        let inputFormat = input.outputFormat(forBus: 0)
        let converter = AVAudioConverter(from: inputFormat, to: analyzerFormat)

        input.installTap(onBus: 0, bufferSize: 4096, format: inputFormat) { buffer, _ in
            guard let converter else {
                continuation.yield(AnalyzerInput(buffer: buffer))
                return
            }
            let ratio = analyzerFormat.sampleRate / inputFormat.sampleRate
            let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 1024
            guard let out = AVAudioPCMBuffer(pcmFormat: analyzerFormat, frameCapacity: capacity) else { return }

            var supplied = false
            var error: NSError?
            converter.convert(to: out, error: &error) { _, status in
                if supplied { status.pointee = .noDataNow; return nil }
                supplied = true
                status.pointee = .haveData
                return buffer
            }
            if error == nil, out.frameLength > 0 {
                continuation.yield(AnalyzerInput(buffer: out))
            }
        }

        engine.prepare()
        try engine.start()
    }
}
