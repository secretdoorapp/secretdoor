import Foundation
import XCTest
@testable import SecretDoorCore

final class VaultExportTests: XCTestCase {
    private func sampleItems() -> (ciphers: [CipherView], folders: [FolderView]) {
        let folder = FolderView(name: "Work")
        var login = CipherView(id: "1", type: .login)
        login.name = "Example, Inc"
        login.notes = "line1\nline \"2\""
        login.folderId = folder.id
        login.isFavorite = true
        login.login.username = "alice"
        login.login.password = "p@ss,word"
        login.login.uris = [LoginUriView(uri: "https://example.com")]
        var card = CipherView(id: "2", type: .card)
        card.name = "Visa"
        card.section = ["number": "4111111111111111"]
        var trashed = CipherView(id: "3", type: .login)
        trashed.name = "Deleted"
        trashed.deletedDate = Date()
        return ([login, card, trashed], [folder])
    }

    func testJsonHasItemsAndSkipsTrash() throws {
        let (ciphers, folders) = sampleItems()
        let output = try VaultExport.export(ciphers: ciphers, folders: folders, format: .json)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: output.data) as? [String: Any])
        XCTAssertEqual(object["encrypted"] as? Bool, false)
        let items = try XCTUnwrap(object["items"] as? [[String: Any]])
        XCTAssertEqual(items.count, 2)
        let login = try XCTUnwrap(items.first { $0["id"] as? String == "1" }?["login"] as? [String: Any])
        XCTAssertEqual(login["password"] as? String, "p@ss,word")
        let card = try XCTUnwrap(items.first { $0["id"] as? String == "2" }?["card"] as? [String: Any])
        XCTAssertEqual(card["number"] as? String, "4111111111111111")
    }

    func testCsvQuotesAndSkipsCards() throws {
        let (ciphers, folders) = sampleItems()
        let output = try VaultExport.export(ciphers: ciphers, folders: folders, format: .csv)
        let text = try XCTUnwrap(String(data: output.data, encoding: .utf8))
        XCTAssertEqual(output.skippedItems, 1)
        XCTAssertTrue(text.hasPrefix("folder,favorite,type,name,notes,fields,reprompt,login_uri,login_username,login_password,login_totp\n"))
        XCTAssertTrue(text.contains("Work,1,login,\"Example, Inc\",\"line1\nline \"\"2\"\"\""))
        XCTAssertTrue(text.contains("\"p@ss,word\""))
        XCTAssertFalse(text.contains("Visa"))
    }

    func testEncryptedJsonRoundTrip() throws {
        let (ciphers, folders) = sampleItems()
        let output = try VaultExport.export(ciphers: ciphers, folders: folders, format: .encryptedJson(filePassword: "file pw"))
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: output.data) as? [String: Any])
        XCTAssertEqual(object["encrypted"] as? Bool, true)
        XCTAssertEqual(object["passwordProtected"] as? Bool, true)
        XCTAssertEqual(object["kdfIterations"] as? Int, 600_000)
        XCTAssertFalse(String(decoding: output.data, as: UTF8.self).contains("p@ss,word"))
        let plain = try VaultExport.decrypt(output.data, filePassword: "file pw")
        XCTAssertTrue(String(decoding: plain, as: UTF8.self).contains("p@ss,word"))
        XCTAssertThrowsError(try VaultExport.decrypt(output.data, filePassword: "wrong"))
    }
}
