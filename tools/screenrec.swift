// 화면 + 컴퓨터 소리를 함께 녹화 (macOS 15+, ScreenCaptureKit)
// 빌드: swiftc -O tools/screenrec.swift -o tools/screenrec
// 사용: tools/screenrec 출력.mov 초
import Foundation
import ScreenCaptureKit
import AVFoundation

@available(macOS 15.0, *)
final class Rec: NSObject, SCRecordingOutputDelegate, SCStreamDelegate {
    var stream: SCStream?
    var stopped = false
    // 도중에 끊기면 새 파일(_2, _3…)로 이어서 녹화한다
    func run(out: URL, seconds: Double) async throws {
        let end = Date().addingTimeInterval(seconds)
        var part = 1
        while Date() < end {
            let url = part == 1 ? out : out.deletingLastPathComponent()
                .appendingPathComponent(out.deletingPathExtension().lastPathComponent + "_\(part).mov")
            do { try await segment(out: url, until: end) } catch { print("구간 오류: \(error)") }
            part += 1
            if Date() < end { print("끊겨서 이어서 녹화 (\(part))"); fflush(stdout); try? await Task.sleep(nanoseconds: 500_000_000) }
        }
    }
    func segment(out: URL, until end: Date) async throws {
        stopped = false
        let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
        guard let display = content.displays.first else { throw NSError(domain: "rec", code: 1) }
        let filter = SCContentFilter(display: display, excludingWindows: [])
        let cfg = SCStreamConfiguration()
        let scale = Int(filter.pointPixelScale)
        cfg.width = display.width * scale
        cfg.height = display.height * scale
        cfg.minimumFrameInterval = CMTime(value: 1, timescale: 30)
        cfg.showsCursor = false
        cfg.capturesAudio = true
        cfg.sampleRate = 48000
        cfg.channelCount = 2
        let s = SCStream(filter: filter, configuration: cfg, delegate: self)
        let rc = SCRecordingOutputConfiguration()
        rc.outputURL = out
        rc.outputFileType = .mov
        rc.videoCodecType = .h264
        let ro = SCRecordingOutput(configuration: rc, delegate: self)
        try s.addRecordingOutput(ro)
        try await s.startCapture()
        stream = s
        print("녹화 시작"); fflush(stdout)
        while Date() < end && !stopped { try await Task.sleep(nanoseconds: 200_000_000) }
        if !stopped { try await s.stopCapture() }
        try await Task.sleep(nanoseconds: 1_000_000_000)
        print("녹화 끝: \(out.path)")
    }
    func recordingOutput(_ o: SCRecordingOutput, didFailWithError e: Error) { print("녹화 오류: \(e)") }
    func stream(_ s: SCStream, didStopWithError e: Error) { print("스트림 오류: \(e)"); stopped = true }
}

let args = CommandLine.arguments
let out = URL(fileURLWithPath: args.count > 1 ? args[1] : "rec.mov")
let secs = args.count > 2 ? Double(args[2]) ?? 10 : 10
if #available(macOS 15.0, *) {
    let sem = DispatchSemaphore(value: 0)
    let r = Rec()
    Task {
        do { try await r.run(out: out, seconds: secs) } catch { print("실패: \(error)") }
        sem.signal()
    }
    sem.wait()
} else { print("macOS 15 이상 필요") }
