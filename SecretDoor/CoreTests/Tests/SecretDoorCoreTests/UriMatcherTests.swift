import XCTest
@testable import SecretDoorCore

final class UriMatcherTests: XCTestCase {
    func testBaseDomain() {
        XCTAssertEqual(UriMatcher.baseDomain(of: "login.example.com"), "example.com")
        XCTAssertEqual(UriMatcher.baseDomain(of: "a.b.example.co.uk"), "example.co.uk")
        XCTAssertEqual(UriMatcher.baseDomain(of: "example.com"), "example.com")
        XCTAssertEqual(UriMatcher.baseDomain(of: "192.168.1.10"), "192.168.1.10")
    }

    func testDefaultMatchesSubdomains() {
        XCTAssertTrue(UriMatcher.matches(uri: "https://example.com", match: nil, target: "https://accounts.example.com/login"))
        XCTAssertTrue(UriMatcher.matches(uri: "example.com", match: nil, target: "example.com"))
        XCTAssertFalse(UriMatcher.matches(uri: "https://example.com", match: nil, target: "https://example.org"))
        XCTAssertFalse(UriMatcher.matches(uri: "https://example.com", match: nil, target: "https://notexample.com"))
        XCTAssertFalse(UriMatcher.matches(uri: "https://bank.co.uk", match: nil, target: "https://other.co.uk"))
    }

    func testOtherMatchTypes() {
        XCTAssertTrue(UriMatcher.matches(uri: "https://a.example.com", match: .host, target: "https://a.example.com/x"))
        XCTAssertFalse(UriMatcher.matches(uri: "https://a.example.com", match: .host, target: "https://b.example.com"))
        XCTAssertTrue(UriMatcher.matches(uri: "https://example.com/app", match: .startsWith, target: "https://example.com/app/login"))
        XCTAssertFalse(UriMatcher.matches(uri: "https://example.com/app", match: .startsWith, target: "https://example.com/other"))
        XCTAssertTrue(UriMatcher.matches(uri: "https://example.com/a", match: .exact, target: "https://example.com/a"))
        XCTAssertFalse(UriMatcher.matches(uri: "https://example.com/a", match: .exact, target: "https://example.com/a/b"))
        XCTAssertTrue(UriMatcher.matches(uri: "^https://.*\\.example\\.com/", match: .regularExpression, target: "https://x.example.com/"))
        XCTAssertFalse(UriMatcher.matches(uri: "https://example.com", match: .never, target: "https://example.com"))
    }
}
