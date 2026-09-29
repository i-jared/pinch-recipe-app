import Foundation

struct DriveFile: Decodable, Sendable {
    let id: String
    let name: String
    let mimeType: String?
    let appProperties: [String: String]?
}

enum DriveError: LocalizedError {
    case http(Int, String)

    var errorDescription: String? {
        switch self {
        case .http(let code, _): "Google Drive responded with an error (\(code))."
        }
    }
}

/// The handful of Google Drive v3 REST calls sync needs. Shared layout with Android:
/// a "Pinch Recipes" folder holding one JSON file per recipe and a "Photos" subfolder.
struct DriveAPI {
    static let folderMime = "application/vnd.google-apps.folder"

    let token: @MainActor () async throws -> String
    let onUnauthorized: @MainActor () -> Void

    private let base = "https://www.googleapis.com/drive/v3/files"
    private let uploadBase = "https://www.googleapis.com/upload/drive/v3/files"

    func userEmail() async throws -> String? {
        let data = try await send(path: "https://www.googleapis.com/drive/v3/about", query: ["fields": "user(emailAddress)"])
        let about = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        return (about?["user"] as? [String: Any])?["emailAddress"] as? String
    }

    func findFolder(appProperty: (String, String), parent: String?) async throws -> String? {
        var q = "mimeType = '\(Self.folderMime)' and trashed = false and appProperties has { key='\(appProperty.0)' and value='\(appProperty.1)' }"
        if let parent { q += " and '\(parent)' in parents" }
        let files = try await list(query: q)
        return files.first?.id
    }

    func createFolder(name: String, parent: String?, appProperties: [String: String]) async throws -> String {
        var metadata: [String: Any] = ["name": name, "mimeType": Self.folderMime, "appProperties": appProperties]
        if let parent { metadata["parents"] = [parent] }
        let body = try JSONSerialization.data(withJSONObject: metadata)
        let data = try await send(path: base, method: "POST", query: ["fields": "id"], body: body, contentType: "application/json; charset=UTF-8")
        let file = try JSONDecoder().decode(DriveFile.self, from: withName(data))
        return file.id
    }

    /// False when the folder was deleted or moved to the trash in Drive.
    func isUsableFolder(_ id: String) async throws -> Bool {
        do {
            let data = try await send(path: "\(base)/\(id)", query: ["fields": "trashed"])
            let object = try JSONSerialization.jsonObject(with: data) as? [String: Any]
            return (object?["trashed"] as? Bool) != true
        } catch DriveError.http(let code, _) where code == 404 {
            return false
        }
    }

    func files(in folder: String) async throws -> [DriveFile] {
        try await list(query: "'\(folder)' in parents and trashed = false")
    }

    private func list(query: String) async throws -> [DriveFile] {
        struct Page: Decodable { let files: [DriveFile]; let nextPageToken: String? }
        var all: [DriveFile] = []
        var pageToken: String?
        repeat {
            var params = [
                "q": query,
                "spaces": "drive",
                "pageSize": "1000",
                "orderBy": "createdTime",
                "fields": "nextPageToken, files(id, name, mimeType, appProperties)",
            ]
            if let pageToken { params["pageToken"] = pageToken }
            let data = try await send(path: base, query: params)
            let page = try JSONDecoder().decode(Page.self, from: data)
            all += page.files
            pageToken = page.nextPageToken
        } while pageToken != nil
        return all
    }

    /// Creates a file, or replaces the content of `existingID`.
    @discardableResult
    func upload(name: String, mimeType: String, data: Data, parent: String, appProperties: [String: String], existingID: String?) async throws -> String {
        var metadata: [String: Any] = ["name": name, "appProperties": appProperties]
        if existingID == nil {
            metadata["parents"] = [parent]
            metadata["mimeType"] = mimeType
        }
        let boundary = "pinch-\(UUID().uuidString)"
        var body = Data()
        body.append("--\(boundary)\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n".data(using: .utf8)!)
        body.append(try JSONSerialization.data(withJSONObject: metadata))
        body.append("\r\n--\(boundary)\r\nContent-Type: \(mimeType)\r\n\r\n".data(using: .utf8)!)
        body.append(data)
        body.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)

        let path = existingID.map { "\(uploadBase)/\($0)" } ?? uploadBase
        let response = try await send(
            path: path,
            method: existingID == nil ? "POST" : "PATCH",
            query: ["uploadType": "multipart", "fields": "id"],
            body: body,
            contentType: "multipart/related; boundary=\(boundary)"
        )
        return try JSONDecoder().decode(DriveFile.self, from: withName(response)).id
    }

    func download(_ id: String) async throws -> Data {
        try await send(path: "\(base)/\(id)", query: ["alt": "media"])
    }

    func delete(_ id: String) async throws {
        _ = try await send(path: "\(base)/\(id)", method: "DELETE")
    }

    // MARK: HTTP

    /// `fields=id` responses have no name; give the decoder one.
    private func withName(_ data: Data) -> Data {
        guard var object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return data }
        if object["name"] == nil { object["name"] = "" }
        return (try? JSONSerialization.data(withJSONObject: object)) ?? data
    }

    private func send(
        path: String,
        method: String = "GET",
        query: [String: String] = [:],
        body: Data? = nil,
        contentType: String? = nil,
        retrying: Bool = false
    ) async throws -> Data {
        var components = URLComponents(string: path)!
        if !query.isEmpty {
            components.queryItems = query.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
        }
        var request = URLRequest(url: components.url!)
        request.httpMethod = method
        request.timeoutInterval = 45
        request.setValue("Bearer \(try await token())", forHTTPHeaderField: "Authorization")
        if let contentType { request.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        request.httpBody = body

        let (data, response) = try await URLSession.shared.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status == 401 && !retrying {
            await onUnauthorized()
            return try await send(path: path, method: method, query: query, body: body, contentType: contentType, retrying: true)
        }
        guard (200..<300).contains(status) else {
            throw DriveError.http(status, String(data: data, encoding: .utf8) ?? "")
        }
        return data
    }
}
