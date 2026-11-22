import XCTest
import CryptoKit
@testable import Pinch

final class ServerTests: XCTestCase {
    func testManagedBrowserSignInBindsTheCallbackAndTokenRequest() throws {
        let authorization = ManagedAuthorization()
        let url = try XCTUnwrap(URLComponents(url: authorization.url, resolvingAgainstBaseURL: false))
        let fields = Dictionary(uniqueKeysWithValues: (url.queryItems ?? []).map { ($0.name, $0.value ?? "") })
        XCTAssertEqual(url.host, "recipes.lmbrt.net")
        XCTAssertEqual(fields["code_challenge_method"], "S256")
        let state = try XCTUnwrap(fields["state"])
        XCTAssertThrowsError(try authorization.exchange(URL(string: "\(ManagedAuthorization.redirect)?code=abc&state=wrong")!))
        XCTAssertThrowsError(try authorization.exchange(URL(string: "https://another.example/?code=abc&state=\(state)")!))

        let data = try authorization.exchange(URL(string: "\(ManagedAuthorization.redirect)?code=abc&state=\(state)")!)
        var form = URLComponents()
        form.percentEncodedQuery = String(decoding: data, as: UTF8.self)
        let request = Dictionary(uniqueKeysWithValues: (form.queryItems ?? []).map { ($0.name, $0.value ?? "") })
        let verifier = try XCTUnwrap(request["code_verifier"])
        let challenge = Data(SHA256.hash(data: Data(verifier.utf8))).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
        XCTAssertEqual(challenge, fields["code_challenge"])
        XCTAssertEqual(request["grant_type"], "authorization_code")
        XCTAssertEqual(request["redirect_uri"], ManagedAuthorization.redirect)
    }

    func testConnectionReportsShortPasswordsInsteadOfSilentlyBlockingSubmission() throws {
        for password in ["", "shortpass", String(repeating: "a", count: 257)] {
            XCTAssertThrowsError(try ServerAPI.validateCredentials(username: "alex", password: password, registrationKey: "", requiresRegistrationKey: false)) { error in
                XCTAssertTrue(error.localizedDescription.lowercased().contains("password"))
            }
        }
        for password in [String(repeating: "a", count: 10), String(repeating: "a", count: 256)] {
            XCTAssertNoThrow(try ServerAPI.validateCredentials(username: "alex", password: password, registrationKey: "", requiresRegistrationKey: false))
        }
        // Python counts this as ten Unicode code points, even though Swift sees five characters.
        XCTAssertNoThrow(try ServerAPI.validateCredentials(username: "alex", password: String(repeating: "e\u{301}", count: 5), registrationKey: "", requiresRegistrationKey: false))
    }

    func testRegistrationKeyIsOnlyRequiredForSignupWithoutAnInvitation() throws {
        XCTAssertNoThrow(try ServerAPI.validateCredentials(username: " alex ", password: "valid-password", registrationKey: "", requiresRegistrationKey: false))
        XCTAssertThrowsError(try ServerAPI.validateCredentials(username: "alex", password: "valid-password", registrationKey: "", requiresRegistrationKey: true)) { error in
            XCTAssertTrue(error.localizedDescription.contains("registration key"))
        }
        XCTAssertNoThrow(try ServerAPI.validateCredentials(username: "alex", password: "valid-password", registrationKey: "host-key", requiresRegistrationKey: true))
    }

    func testConnectionExplainsInvalidUsernames() throws {
        for name in ["", "  ", "ab", "name with spaces", "alex@example.com", String(repeating: "a", count: 65)] {
            XCTAssertThrowsError(try ServerAPI.validateCredentials(username: name, password: "valid-password", registrationKey: "", requiresRegistrationKey: false)) { error in
                XCTAssertTrue(error.localizedDescription.contains("username"))
            }
        }
        XCTAssertNoThrow(try ServerAPI.validateCredentials(username: " Alex_1.test-name ", password: "valid-password", registrationKey: "", requiresRegistrationKey: false))
    }

    func testServerAddressesRequireTLSWithoutEmbeddedSecrets() throws {
        XCTAssertEqual(try ServerAPI.serverURL(" https://recipes.example.com/pinch ").absoluteString, "https://recipes.example.com/pinch")
        for address in ["http://recipes.example.com", "https://user:password@example.com", "https://example.com?token=secret", "https://example.com#fragment", "file:///tmp/recipes", ""] {
            XCTAssertThrowsError(try ServerAPI.serverURL(address), address)
        }
    }

    func testRemoteNamesCannotEscapeLocalStorage() throws {
        var recipe = Recipe(id: "recipe-1", photos: ["page-1.jpg"])
        XCTAssertNoThrow(try ServerAPI.validate(recipe))
        recipe.id = "../escape"
        XCTAssertThrowsError(try ServerAPI.validate(recipe))
        recipe.id = "recipe-1"
        for name in ["../escape.jpg", "/absolute.jpg", "folder/page.jpg", "page.jpg?x=1"] {
            recipe.photos = [name]
            XCTAssertThrowsError(try ServerAPI.validate(recipe))
        }
    }

    func testSyncTimestampsMatchSerializedPrecision() throws {
        for fraction in [0.0001, 0.0006, 0.1234, 0.56789, 0.9999] {
            let original = Recipe(updatedAt: Date(timeIntervalSince1970: 1_750_000_000 + fraction))
            let restored = try PinchJSON.decoder.decode(Recipe.self, from: PinchJSON.encoder.encode(original))
            XCTAssertEqual(PinchJSON.millis(original.updatedAt), PinchJSON.millis(restored.updatedAt))
        }
    }

    func testAPIUsesBasePathAuthenticationAndReportsServerErrors() async throws {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ServerTestProtocol.self]
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel(); ServerTestProtocol.handler = nil }
        var api = ServerAPI(base: URL(string: "https://recipes.example.com/pinch")!, token: "test-session")
        api.session = session
        ServerTestProtocol.handler = { request in
            XCTAssertEqual(request.url?.path, "/pinch/v1/groups/join")
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer test-session")
            return (404, Data("{\"detail\":\"Invite code not found on this server.\"}".utf8))
        }
        do {
            _ = try await api.json("groups/join", fields: ["code": "invalid-invite"])
            XCTFail("Expected a server error")
        } catch {
            XCTAssertEqual(error.localizedDescription, "Invite code not found on this server.")
        }
    }
}

private final class ServerTestProtocol: URLProtocol {
    static var handler: ((URLRequest) -> (Int, Data))?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        guard let handler = Self.handler else { return }
        let (status, data) = handler(request)
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() { }
}

final class GroupModelTests: XCTestCase {
    func testInvitationCarriesServerPathAndRejectsUnsafeAddresses() throws {
        let invitation = try GroupInvitation.parse("https://recipes.example.com/pinch/join#invite-code")
        XCTAssertEqual(invitation.server, "https://recipes.example.com/pinch")
        XCTAssertEqual(invitation.code, "invite-code")
        XCTAssertEqual(try GroupInvitation.parse("pinch://join?server=https%3A%2F%2Frecipes.example.com%2Fpinch&code=invite-code"), invitation)
        for link in ["http://recipes.example.com/join#code", "https://user:password@example.com/join#code", "https://example.com/join?secret=x#code", "pinch://join?server=http%3A%2F%2Fexample.com&code=code", "https://example.com/join#../invalid", "https://example.com/join"] {
            XCTAssertThrowsError(try GroupInvitation.parse(link), link)
        }
    }

    func testSavedCopyAttributionSurvivesDiskAndSyncEncoding() throws {
        var recipe = Recipe(title: "Family bread")
        recipe.origin = RecipeOrigin(groupId: "group-1", shareId: "share-1", groupName: "Family", sharedBy: "alex")
        let restored = try PinchJSON.decoder.decode(Recipe.self, from: PinchJSON.encoder.encode(recipe))
        XCTAssertEqual(restored.origin, recipe.origin)
        var old = recipe; old.origin = nil
        XCTAssertNil(try PinchJSON.decoder.decode(Recipe.self, from: PinchJSON.encoder.encode(old)).origin)
    }
}

final class CookbookTests: XCTestCase {
    func testOfflineMembershipMergeKeepsNewContributionsAndRemovals() throws {
        let old = Date(timeIntervalSince1970: 1000)
        let newer = old.addingTimeInterval(2)
        let device = Cookbook(id: "book", name: "Dinners", updatedAt: old, entries: [CookbookEntry(recipeId: "mine", updatedAt: newer, removed: true)])
        let server = Cookbook(id: "book", name: "Dinners", updatedAt: old, entries: [CookbookEntry(recipeId: "mine", updatedAt: old), CookbookEntry(recipeId: "contributed", updatedAt: newer)])
        let merged = device.merging(server)
        XCTAssertEqual(merged.recipeIDs, ["contributed"])
        XCTAssertEqual(server.merging(device), merged)
        let restored = try PinchJSON.decoder.decode(Cookbook.self, from: PinchJSON.encoder.encode(merged))
        XCTAssertEqual(restored.recipeIDs, merged.recipeIDs)
    }

    @MainActor
    func testPrivateCookbooksPersistWithoutAnAccountAndKeepRecipesWhenRemoved() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let pages = PhotoStore(directory: root.appendingPathComponent("photos"))
        let directory = root.appendingPathComponent("recipes")
        let store = RecipeStore(directory: directory, photos: pages)
        let recipe = Recipe(id: "bread", title: "Bread")
        store.save(recipe)
        let one = store.createCookbook("Dinners")
        let two = store.createCookbook("Family")
        store.setMembership(recipe.id, in: one.id, included: true)
        store.setMembership(recipe.id, in: two.id, included: true)
        let reopened = RecipeStore(directory: directory, photos: pages)
        XCTAssertEqual(reopened.cookbooks.filter { $0.recipeIDs.contains(recipe.id) }.count, 2)
        reopened.setMembership(recipe.id, in: one.id, included: false)
        reopened.deleteCookbook(two.id)
        XCTAssertNotNil(reopened.recipe(recipe.id))
        XCTAssertFalse(reopened.cookbooks.first { $0.id == one.id }!.recipeIDs.contains(recipe.id))
        XCTAssertNotNil(reopened.cookbooks.first { $0.id == two.id }!.deletedAt)
    }
}
