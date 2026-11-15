// 화면 + 컴퓨터 소리를 함께 녹화 (macOS 15+, ScreenCaptureKit)
// 빌드: swiftc -O tools/screenrec.swift -o tools/screenrec
// 사용: tools/screenrec 출력.mov 초 [창 이름 일부]  (창 이름을 주면 그 창만 녹화)
import Foundation
import ScreenCaptureKit
import AVFoundation
import AppKit

@available(macOS 15.0, *)
final class Rec: NSObject, SCRecordingOutputDelegate, SCStreamDelegate {
    var windowName: String?
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
        let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: windowName == nil)
        let filter: SCContentFilter
        let cfg = SCStreamConfiguration()
        if let name = windowName {
            // 창 하나만 녹화 (다른 창이 가려도 그 창 내용만 찍힌다)
            guard let w = content.windows.filter({ ($0.title ?? "").contains(name) && $0.frame.width > 300 })
                    .max(by: { $0.frame.width * $0.frame.height < $1.frame.width * $1.frame.height }) else {
                print("창을 찾지 못함: \(name)"); throw NSError(domain: "rec", code: 2) }
            filter = SCContentFilter(desktopIndependentWindow: w)
            let scale = CGFloat(filter.pointPixelScale)
            cfg.width = Int(w.frame.width * scale); cfg.height = Int(w.frame.height * scale)
            print("창 녹화: \(w.title ?? "") \(cfg.width)x\(cfg.height)")
        } else {
            guard let display = content.displays.first else { throw NSError(domain: "rec", code: 1) }
            filter = SCContentFilter(display: display, excludingWindows: [])
            let scale = Int(filter.pointPixelScale)
            cfg.width = display.width * scale
            cfg.height = display.height * scale
        }
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

_ = NSApplication.shared   // 창 녹화에 필요한 그래픽 초기화
let args = CommandLine.arguments
let out = URL(fileURLWithPath: args.count > 1 ? args[1] : "rec.mov")
let secs = args.count > 2 ? Double(args[2]) ?? 10 : 10
if #available(macOS 15.0, *) {
    let sem = DispatchSemaphore(value: 0)
    let r = Rec()
    if args.count > 3 { r.windowName = args[3] }
    Task {
        do { try await r.run(out: out, seconds: secs) } catch { print("실패: \(error)") }
        sem.signal()
    }
    sem.wait()
} else { print("macOS 15 이상 필요") }
