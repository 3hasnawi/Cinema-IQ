import Foundation
import XCTest
@testable import CinemaCore

final class IPTVPopupPlacementTests: XCTestCase {
    func testPopoverPointsAtItsIconWithoutCoveringIt() {
        let result = IPTVPopupPlacement.above(window: CGSize(width: 1920, height: 1080),
            anchor: CGRect(x: 1280, y: 988, width: 46, height: 46), popup: CGSize(width: 360, height: 420))
        XCTAssertEqual(result.origin.x + 180, 1303)
        XCTAssertEqual(result.origin.y + 420, 978)
        XCTAssertEqual(result.pivot, 0.5)
    }
    func testEdgeIconsKeepMenusAndTailInsideTheScreen() {
        for x: CGFloat in [0, 1890] {
            let result = IPTVPopupPlacement.above(window: CGSize(width: 1920, height: 1080),
                anchor: CGRect(x: x, y: 988, width: 46, height: 46), popup: CGSize(width: 600, height: 580))
            XCTAssertGreaterThanOrEqual(result.origin.x, 32)
            XCTAssertLessThanOrEqual(result.origin.x + 600, 1888)
            XCTAssertGreaterThanOrEqual(result.pivot, 0.08)
            XCTAssertLessThanOrEqual(result.pivot, 0.92)
        }
    }
}
