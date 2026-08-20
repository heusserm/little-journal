import AVFoundation
import ComposeApp
import XCTest
@testable import Little_Journal

/// Records what the recognizer tells the app, so the Kotlin/Swift boundary can
/// be exercised without a real microphone.
final class RecordingListener: NSObject, TranscriberListener {
    private(set) var partials: [String] = []
    private(set) var finals: [String] = []
    private(set) var statuses: [String] = []
    private(set) var errors: [String] = []

    var onAnything: (() -> Void)?

    func onPartial(text: String) { partials.append(text); onAnything?() }
    func onFinal(text: String) { finals.append(text); onAnything?() }
    func onStatus(message: String) { statuses.append(message); onAnything?() }
    func onError(message: String) { errors.append(message); onAnything?() }
}

final class TranscriberLifecycleTests: XCTestCase {

    func testStoppingBeforeStartingLeavesItUsable() {
        let transcriber = IosTranscriber()

        transcriber.stop()   // tears down an engine that was never started

        XCTAssertTrue(transcriber.isAvailable, "a no-op stop must not disable the recognizer")
    }

    func testStoppingTwiceLeavesItUsable() {
        let transcriber = IosTranscriber()

        transcriber.stop()
        transcriber.stop()

        XCTAssertTrue(transcriber.isAvailable, "stop must be idempotent, not destructive")
    }

    func testStartThenImmediateStopProducesNoTranscript() {
        let transcriber = IosTranscriber()
        let listener = RecordingListener()

        transcriber.start(listener: listener)
        transcriber.stop()   // cancels the start task mid-flight

        XCTAssertTrue(listener.finals.isEmpty, "nothing was spoken, so nothing may be transcribed")
        XCTAssertTrue(listener.partials.isEmpty)
    }

    /// The boundary that matters: a Swift class satisfying a Kotlin-declared
    /// protocol, with Kotlin able to call back into it. If a Kotlin upgrade
    /// changed the generated protocol, this is what would break first.
    func testASwiftListenerSatisfiesTheKotlinProtocol() {
        let listener = RecordingListener()

        listener.onStatus(message: "listening")
        listener.onPartial(text: "half a sen")
        listener.onFinal(text: "half a sentence.")
        listener.onError(message: "nope")

        XCTAssertEqual(listener.statuses, ["listening"])
        XCTAssertEqual(listener.partials, ["half a sen"])
        XCTAssertEqual(listener.finals, ["half a sentence."])
        XCTAssertEqual(listener.errors, ["nope"])
    }

    /// On the Simulator there are no speech assets, so starting must fail —
    /// but it must fail *by telling the app*, not by hanging or crashing.
    func testStartingReportsBackRatherThanFailingSilently() {
        let listener = RecordingListener()
        let told = expectation(description: "the recognizer says something")
        told.assertForOverFulfill = false
        listener.onAnything = { told.fulfill() }

        let transcriber = IosTranscriber()
        transcriber.start(listener: listener)

        wait(for: [told], timeout: 30)
        transcriber.stop()

        XCTAssertFalse(
            listener.statuses.isEmpty && listener.errors.isEmpty,
            "pressing Talk must produce either progress or a reason it cannot"
        )
    }
}
