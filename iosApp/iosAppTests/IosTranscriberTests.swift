import AVFoundation
import XCTest
@testable import Little_Journal

/// Covers the parts of IosTranscriber that can run without a microphone or
/// Apple's speech stack.
///
/// The recognizer path is deliberately absent: the Simulator ships no ASR
/// assets at all, so any test touching SpeechAnalyzer would fail for reasons
/// that say nothing about this code. Buffer conversion is the piece with real
/// arithmetic in it, and it runs on the realtime audio thread, so it is the
/// piece worth pinning.
final class IosTranscriberTests: XCTestCase {

    private func buffer(sampleRate: Double, frames: AVAudioFrameCount) -> AVAudioPCMBuffer {
        let format = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 1)!
        let buf = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames)!
        buf.frameLength = frames
        // A quiet ramp, so the data is at least not uniformly zero.
        if let ch = buf.floatChannelData?[0] {
            for i in 0..<Int(frames) { ch[i] = Float(i % 100) / 1000.0 }
        }
        return buf
    }

    func testNilConverterPassesTheBufferThroughUnchanged() {
        let input = buffer(sampleRate: 16_000, frames: 512)
        let format = AVAudioFormat(standardFormatWithSampleRate: 16_000, channels: 1)!

        let out = IosTranscriber.convert(input, with: nil, to: format)

        XCTAssertTrue(out === input, "with no converter the buffer should be forwarded as-is")
    }

    func testDownsamplingProducesProportionallyFewerFrames() throws {
        let inputFormat = AVAudioFormat(standardFormatWithSampleRate: 48_000, channels: 1)!
        let outputFormat = AVAudioFormat(standardFormatWithSampleRate: 16_000, channels: 1)!
        let converter = try XCTUnwrap(AVAudioConverter(from: inputFormat, to: outputFormat))
        let input = buffer(sampleRate: 48_000, frames: 4800)   // 100 ms

        let out = try XCTUnwrap(IosTranscriber.convert(input, with: converter, to: outputFormat))

        XCTAssertEqual(out.format.sampleRate, 16_000)

        // The arithmetic answer is 1600 frames (100 ms at 16 kHz), but the real
        // one is ~1360: AVAudioConverter primes its resampling filter, so the
        // first buffer comes back short. Pin the property that matters -- about
        // a third as many frames, and never more than went in -- rather than a
        // count that depends on filter internals.
        XCTAssertLessThan(out.frameLength, input.frameLength)
        XCTAssertEqual(Double(out.frameLength) / 4800.0, 1.0 / 3.0, accuracy: 0.08)
    }

    func testConversionKeepsAudioRatherThanEmittingSilence() throws {
        let inputFormat = AVAudioFormat(standardFormatWithSampleRate: 48_000, channels: 1)!
        let outputFormat = AVAudioFormat(standardFormatWithSampleRate: 16_000, channels: 1)!
        let converter = try XCTUnwrap(AVAudioConverter(from: inputFormat, to: outputFormat))

        let out = try XCTUnwrap(IosTranscriber.convert(buffer(sampleRate: 48_000, frames: 4800),
                                                       with: converter, to: outputFormat))

        let samples = try XCTUnwrap(out.floatChannelData?[0])
        let peak = (0..<Int(out.frameLength)).map { abs(samples[$0]) }.max() ?? 0
        XCTAssertGreaterThan(peak, 0, "a converted buffer of real audio must not be silent")
    }

    func testEnvironmentIsNamedHonestly() {
        let note = IosTranscriber().environmentNote
        #if targetEnvironment(simulator)
        XCTAssertTrue(note.contains("Simulator"), "on the Simulator it must say so")
        #else
        XCTAssertFalse(note.contains("Simulator"), "on a device it must not blame the Simulator")
        #endif
    }

    func testTranscriberReportsItselfAvailable() {
        XCTAssertTrue(IosTranscriber().isAvailable)
    }
}
