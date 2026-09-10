import XCTest
@testable import PurchaseSales

final class FinanceTests: XCTestCase {
    func testStandardVATFromGross() {
        let value = Finance.fromGross(120_000, .standard)
        XCTAssertEqual(value.netPence, 100_000)
        XCTAssertEqual(value.vatPence, 20_000)
        XCTAssertEqual(value.grossPence, 120_000)
    }

    func testReverseVATFromGross() {
        let value = Finance.fromGross(100_000, .reverse)
        XCTAssertEqual(value.netPence, 100_000)
        XCTAssertEqual(value.vatPence, 0)
        XCTAssertEqual(value.reverseVatPence, 20_000)
    }

    func testUnitGrossIsMultipliedByQuantity() {
        let unit = Finance.pence("120.00")
        let value = Finance.fromGross(unit * 3, .standard)
        XCTAssertEqual(value.grossPence, 36_000)
        XCTAssertEqual(value.netPence, 30_000)
        XCTAssertEqual(value.vatPence, 6_000)
    }
}

