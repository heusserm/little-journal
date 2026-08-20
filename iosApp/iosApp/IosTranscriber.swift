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
    var environmentNote: String {
        #if targetEnvironment(simulator)
        return "Simulator (no speech assets exist here)"
        #else
        return "This device"
        #endif
    }

    private let engine = AVAudioEngine()
    private var analyzer: SpeechAnalyzer?
    private var continuation: AsyncStream<AnalyzerInput>.Continuation?
    private var resultsTask: Task<Void, Never>?
    private var startTask: Task<Void, Never>?

    /// Set by stop(). Startup is a sequence of awaits, and cancelling the task
    /// does not stop the work already in flight -- without this, a user who taps
    /// Talk and immediately Stop can have startEngine() install a tap on an
    /// engine that stop() has already torn down, which crashes.
    private var stopped = false

    private var shouldAbort: Bool { stopped || Task.isCancelled }

    // MARK: Transcriber

    func start(listener: TranscriberListener) {
        stopped = false
        startTask = Task { await self.begin(listener) }
    }

    func stop() {
        stopped = true
        startTask?.cancel()
        engine.stop()
        engine.inputNode.removeTap(onBus: 0)
        continuation?.finish()
        continuation = nil

        let analyzer = self.analyzer
        self.analyzer = nil
        Task { try? await analyzer?.finalizeAndFinishThroughEndOfInput() }

        resultsTask?.cancel()
        resultsTask = nil

        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    // MARK: orchestration

    private func begin(_ listener: TranscriberListener) async {
        do {
            guard await authorize(listener), !shouldAbort else { return }
            try configureSession()
            guard let transcriber = try await prepareTranscriber(listener), !shouldAbort else { return }
            try await startAnalysis(transcriber, listener)
            guard !shouldAbort else { return }
            onMain { listener.onStatus(message: "Listening") }
        } catch {
            onMain { listener.onError(message: error.localizedDescription) }
        }
    }

    // MARK: steps

    /// Microphone and speech recognition are *separate* grants. Without the
    /// second, the OS refuses to subscribe the app to any ASR asset, which
    /// surfaces as the misleading "not subscribed to transcription.en".
    private func authorize(_ listener: TranscriberListener) async -> Bool {
        onMain { listener.onStatus(message: "Asking for the microphone") }
        guard await AVAudioApplication.requestRecordPermission() else {
            onMain { listener.onError(message: "Microphone access denied. Enable it in Settings.") }
            return false
        }

        onMain { listener.onStatus(message: "Asking for speech recognition") }
        let speech: SFSpeechRecognizerAuthorizationStatus = await withCheckedContinuation { cont in
            SFSpeechRecognizer.requestAuthorization { cont.resume(returning: $0) }
        }
        guard speech == .authorized else {
            onMain {
                listener.onError(
                    message: "Speech recognition not authorised (\(speech.rawValue)). "
                        + "Enable it in Settings > Little Journal."
                )
            }
            return false
        }
        return true
    }

    private func configureSession() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.record, mode: .measurement, options: [.duckOthers])
        try session.setActive(true, options: .notifyOthersOnDeactivation)
    }

    /// Resolves the locale and makes sure its model is installed.
    private func prepareTranscriber(_ listener: TranscriberListener) async throws -> SpeechTranscriber? {
        let wanted = Locale(identifier: "en-US")
        let supported = await SpeechTranscriber.supportedLocales
        let locale = await SpeechTranscriber.supportedLocale(equivalentTo: wanted) ?? wanted

        // An empty list is NOT a reason to stop. On a real device it can simply
        // mean nothing is installed yet, which is exactly the state a first run
        // is in — bailing here would block the very download that fixes it.
        if supported.isEmpty {
            onMain { listener.onStatus(message: "\(self.environmentNote) reports 0 installed locales; trying anyway") }
        }

        let transcriber = SpeechTranscriber(locale: locale, preset: .progressiveTranscription)

        // Reserve the locale so its asset is attributed to this app.
        do {
            _ = try await AssetInventory.reserve(locale: locale)
        } catch {
            // Do not swallow this — it decides whether the check below can work.
            onMain { listener.onStatus(message: "Locale reserve failed: \(error.localizedDescription)") }
        }

        let status = await AssetInventory.status(forModules: [transcriber])
        guard status != .installed else { return transcriber }

        onMain { listener.onStatus(message: "Model status \(status) — downloading…") }
        guard let request = try await AssetInventory.assetInstallationRequest(supporting: [transcriber]) else {
            onMain {
                listener.onError(message: "No installable model for \(locale.identifier). \(self.environmentNote).")
            }
            return nil
        }
        try await request.downloadAndInstall()
        onMain { listener.onStatus(message: "Model installed") }
        return transcriber
    }

    private func startAnalysis(
        _ transcriber: SpeechTranscriber,
        _ listener: TranscriberListener
    ) async throws {
        guard let analyzerFormat = await SpeechAnalyzer.bestAvailableAudioFormat(compatibleWith: [transcriber]) else {
            onMain { listener.onError(message: "No compatible audio format on this device.") }
            return
        }

        let (stream, continuation) = AsyncStream<AnalyzerInput>.makeStream()
        self.continuation = continuation

        let analyzer = SpeechAnalyzer(modules: [transcriber])
        self.analyzer = analyzer
        try await analyzer.start(inputSequence: stream)

        guard !shouldAbort else { return }

        resultsTask = Task { [weak self] in
            await self?.pump(transcriber, listener)
        }
        try startEngine(convertingTo: analyzerFormat, into: continuation)
    }

    /// Drains recognizer results until the stream ends.
    private func pump(_ transcriber: SpeechTranscriber, _ listener: TranscriberListener) async {
        do {
            for try await result in transcriber.results {
                let piece = String(result.text.characters)
                let isFinal = result.isFinal
                onMain {
                    if isFinal {
                        listener.onFinal(text: piece)
                    } else {
                        listener.onPartial(text: piece)
                    }
                }
            }
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
            guard let converted = Self.convert(buffer, with: converter, to: analyzerFormat) else { return }
            continuation.yield(AnalyzerInput(buffer: converted))
        }

        engine.prepare()
        try engine.start()
    }

    /// Runs on the realtime audio thread. Returns nil when there is nothing to send.
    static func convert(
        _ buffer: AVAudioPCMBuffer,
        with converter: AVAudioConverter?,
        to format: AVAudioFormat
    ) -> AVAudioPCMBuffer? {
        guard let converter else { return buffer }

        let ratio = format.sampleRate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 1024
        guard let out = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: capacity) else { return nil }

        var supplied = false
        var error: NSError?
        converter.convert(to: out, error: &error) { _, status in
            if supplied { status.pointee = .noDataNow; return nil }
            supplied = true
            status.pointee = .haveData
            return buffer
        }
        guard error == nil, out.frameLength > 0 else { return nil }
        return out
    }

    private func onMain(_ block: @escaping () -> Void) {
        if Thread.isMainThread { block() } else { DispatchQueue.main.async(execute: block) }
    }
}
