// 자막 그림 만들기: 1920x1080 투명 PNG. 영상 양옆 빈칸(왼쪽·오른쪽 431px)에 글자를 넣는다.
// 사용: caption out.png "구분" "제목" "설명(줄바꿈 \n)" "오른쪽 표시(예: ▶▶ 3배속)" [card]
// card를 주면 화면 전체를 덮는 제목 카드(배경 있음)를 만든다.
import AppKit

let a = CommandLine.arguments
let out = a[1], label = a[2], title = a[3], body = a[4].replacingOccurrences(of: "\\n", with: "\n")
let right = a.count > 5 ? a[5] : ""
let card = a.count > 6 && a[6] == "card"
let W = 1920, H = 1080
let accent = NSColor(red: 0.42, green: 0.86, blue: 0.69, alpha: 1)
let text = NSColor(white: 0.96, alpha: 1), muted = NSColor(white: 0.70, alpha: 1)
func font(_ size: CGFloat, _ w: NSFont.Weight) -> NSFont {
    let name = w == .bold ? "AppleSDGothicNeo-Bold" : (w == .semibold ? "AppleSDGothicNeo-SemiBold" : "AppleSDGothicNeo-Regular")
    return NSFont(name: name, size: size) ?? NSFont.systemFont(ofSize: size, weight: w)
}
func draw(_ s: String, _ f: NSFont, _ c: NSColor, _ rect: NSRect, _ align: NSTextAlignment = .left, lineGap: CGFloat = 8) {
    let p = NSMutableParagraphStyle(); p.alignment = align; p.lineSpacing = lineGap; p.lineBreakMode = .byWordWrapping
    (s as NSString).draw(with: rect, options: [.usesLineFragmentOrigin], attributes: [.font: f, .foregroundColor: c, .paragraphStyle: p])
}
let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: W, pixelsHigh: H, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
NSGraphicsContext.saveGraphicsState()
let ctx = NSGraphicsContext(bitmapImageRep: rep)!
NSGraphicsContext.current = ctx
// 위에서 아래로 좌표를 쓰기 위해 뒤집고, 글자는 flipped 컨텍스트로 바로 세운다
let cg = ctx.cgContext
cg.translateBy(x: 0, y: CGFloat(H)); cg.scaleBy(x: 1, y: -1)
let fctx = NSGraphicsContext(cgContext: cg, flipped: true)
NSGraphicsContext.current = fctx

if card {
    NSColor(red: 0.05, green: 0.06, blue: 0.08, alpha: 1).setFill(); NSRect(x: 0, y: 0, width: W, height: H).fill()
    draw(label, font(30, .semibold), accent, NSRect(x: 0, y: 330, width: W, height: 50), .center)
    draw(title, font(84, .bold), text, NSRect(x: 0, y: 390, width: W, height: 130), .center)
    draw(body, font(34, .regular), muted, NSRect(x: 260, y: 545, width: W - 520, height: 300), .center, lineGap: 14)
} else {
    let x: CGFloat = 40, w: CGFloat = 360
    if !label.isEmpty { draw(label, font(24, .semibold), accent, NSRect(x: x, y: 380, width: w, height: 40)) }
    draw(title, font(44, .bold), text, NSRect(x: x, y: 420, width: w, height: 140), lineGap: 4)
    let tf = font(44, .bold)
    let th = (title as NSString).boundingRect(with: NSSize(width: w, height: 400), options: [.usesLineFragmentOrigin], attributes: [.font: tf]).height
    draw(body, font(27, .regular), muted, NSRect(x: x, y: 420 + th + 24, width: w, height: 400), lineGap: 12)
    // 오른쪽: 앱 이름 + 배속 표시
    draw("차쉼", font(40, .bold), text, NSRect(x: 1529, y: 60, width: 351, height: 60), .right)
    draw("기다리는 시간을 쉬는 시간으로", font(20, .regular), muted, NSRect(x: 1529, y: 112, width: 351, height: 40), .right)
    if !right.isEmpty { draw(right, font(28, .semibold), accent, NSRect(x: 1529, y: 980, width: 351, height: 50), .right) }
}
NSGraphicsContext.restoreGraphicsState()
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: out))
