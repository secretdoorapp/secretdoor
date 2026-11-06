import Compression
import Foundation

nonisolated public enum ZipError: Error, Equatable {
    case notAZip
    case damaged(String)
    case unsupported(String)
}

/// Minimal ZIP support for backup payloads: reads stored and deflated entries (via the central
/// directory, so entries written with data descriptors by Java's `ZipOutputStream` work) and
/// writes entries that Java's `ZipInputStream` can read.
nonisolated public enum Zip {
    public struct Entry: Equatable {
        public let name: String
        public let data: Data
        public init(name: String, data: Data) {
            self.name = name
            self.data = data
        }
    }

    // MARK: Reading

    public static func read(_ archive: Data) throws -> [Entry] {
        let bytes = [UInt8](archive)
        guard bytes.count >= 22 else { throw ZipError.notAZip }
        // Find the end of central directory record (it may be followed by a comment).
        var eocd = -1
        var index = bytes.count - 22
        let lowest = max(0, bytes.count - 22 - 0xffff)
        while index >= lowest {
            if bytes[index] == 0x50, bytes[index + 1] == 0x4b, bytes[index + 2] == 0x05, bytes[index + 3] == 0x06 {
                eocd = index
                break
            }
            index -= 1
        }
        guard eocd >= 0 else { throw ZipError.notAZip }
        let entryCount = Int(u16(bytes, eocd + 10))
        let directorySize = Int(u32(bytes, eocd + 12))
        let directoryOffset = Int(u32(bytes, eocd + 16))
        guard entryCount != 0xffff, directoryOffset != 0xffff_ffff else { throw ZipError.unsupported("ZIP64") }
        guard directoryOffset + directorySize <= bytes.count else { throw ZipError.damaged("central directory") }

        var entries = [Entry]()
        var position = directoryOffset
        for _ in 0..<entryCount {
            guard position + 46 <= bytes.count, u32(bytes, position) == 0x0201_4b50 else {
                throw ZipError.damaged("central directory entry")
            }
            let method = u16(bytes, position + 10)
            let crc = u32(bytes, position + 16)
            let compressedSize = Int(u32(bytes, position + 20))
            let size = Int(u32(bytes, position + 24))
            let nameLength = Int(u16(bytes, position + 28))
            let extraLength = Int(u16(bytes, position + 30))
            let commentLength = Int(u16(bytes, position + 32))
            let localOffset = Int(u32(bytes, position + 42))
            guard position + 46 + nameLength <= bytes.count else { throw ZipError.damaged("entry name") }
            let name = String(decoding: bytes[(position + 46)..<(position + 46 + nameLength)], as: UTF8.self)
            position += 46 + nameLength + extraLength + commentLength

            guard localOffset + 30 <= bytes.count, u32(bytes, localOffset) == 0x0403_4b50 else {
                throw ZipError.damaged("local header")
            }
            let dataStart = localOffset + 30 + Int(u16(bytes, localOffset + 26)) + Int(u16(bytes, localOffset + 28))
            guard dataStart + compressedSize <= bytes.count else { throw ZipError.damaged("entry data") }
            if name.hasSuffix("/") { continue }
            let compressed = Data(bytes[dataStart..<(dataStart + compressedSize)])
            let data: Data
            switch method {
            case 0: data = compressed
            case 8: data = try inflate(compressed, expectedSize: size)
            default: throw ZipError.unsupported("compression method \(method)")
            }
            guard data.count == size, CRC32.checksum(data) == crc else { throw ZipError.damaged(name) }
            entries.append(Entry(name: name, data: data))
        }
        return entries
    }

    private static func inflate(_ data: Data, expectedSize: Int) throws -> Data {
        if expectedSize == 0 { return Data() }
        var output = Data(count: expectedSize)
        let written = output.withUnsafeMutableBytes { outputBuffer in
            data.withUnsafeBytes { inputBuffer in
                compression_decode_buffer(
                    outputBuffer.bindMemory(to: UInt8.self).baseAddress!, expectedSize,
                    inputBuffer.bindMemory(to: UInt8.self).baseAddress!, data.count,
                    nil, COMPRESSION_ZLIB
                )
            }
        }
        guard written == expectedSize else { throw ZipError.damaged("deflate stream") }
        return output
    }

    // MARK: Writing

    /// Builds a ZIP archive. Entries whose names end in `.json` are deflated; others (already
    /// encrypted attachments) are stored.
    public static func write(_ entries: [Entry]) throws -> Data {
        var archive = Data()
        var directory = Data()
        for entry in entries {
            let nameBytes = Data(entry.name.utf8)
            let crc = CRC32.checksum(entry.data)
            var method: UInt16 = 0
            var payload = entry.data
            if entry.name.hasSuffix(".json"), let compressed = deflate(entry.data), compressed.count < entry.data.count {
                method = 8
                payload = compressed
            }
            guard archive.count < 0xffff_ffff, payload.count < 0xffff_ffff else { throw ZipError.unsupported("ZIP64") }
            let offset = UInt32(archive.count)
            let flags: UInt16 = 0x0800 // UTF-8 names
            let (dosTime, dosDate) = dosDateTime(Date())

            var local = Data()
            local.appendLE32(0x0403_4b50)
            local.appendLE16(20)
            local.appendLE16(flags)
            local.appendLE16(method)
            local.appendLE16(dosTime)
            local.appendLE16(dosDate)
            local.appendLE32(crc)
            local.appendLE32(UInt32(payload.count))
            local.appendLE32(UInt32(entry.data.count))
            local.appendLE16(UInt16(nameBytes.count))
            local.appendLE16(0)
            archive.append(local)
            archive.append(nameBytes)
            archive.append(payload)

            directory.appendLE32(0x0201_4b50)
            directory.appendLE16(20)
            directory.appendLE16(20)
            directory.appendLE16(flags)
            directory.appendLE16(method)
            directory.appendLE16(dosTime)
            directory.appendLE16(dosDate)
            directory.appendLE32(crc)
            directory.appendLE32(UInt32(payload.count))
            directory.appendLE32(UInt32(entry.data.count))
            directory.appendLE16(UInt16(nameBytes.count))
            directory.appendLE16(0) // extra
            directory.appendLE16(0) // comment
            directory.appendLE16(0) // disk
            directory.appendLE16(0) // internal attributes
            directory.appendLE32(0) // external attributes
            directory.appendLE32(offset)
            directory.append(nameBytes)
        }
        guard entries.count < 0xffff else { throw ZipError.unsupported("ZIP64") }
        let directoryOffset = UInt32(archive.count)
        archive.append(directory)
        archive.appendLE32(0x0605_4b50)
        archive.appendLE16(0)
        archive.appendLE16(0)
        archive.appendLE16(UInt16(entries.count))
        archive.appendLE16(UInt16(entries.count))
        archive.appendLE32(UInt32(directory.count))
        archive.appendLE32(directoryOffset)
        archive.appendLE16(0)
        return archive
    }

    private static func deflate(_ data: Data) -> Data? {
        guard !data.isEmpty else { return nil }
        let capacity = data.count + data.count / 10 + 1024
        var output = Data(count: capacity)
        let written = output.withUnsafeMutableBytes { outputBuffer in
            data.withUnsafeBytes { inputBuffer in
                compression_encode_buffer(
                    outputBuffer.bindMemory(to: UInt8.self).baseAddress!, capacity,
                    inputBuffer.bindMemory(to: UInt8.self).baseAddress!, data.count,
                    nil, COMPRESSION_ZLIB
                )
            }
        }
        return written > 0 ? output.prefix(written) : nil
    }

    private static func dosDateTime(_ date: Date) -> (UInt16, UInt16) {
        let components = Calendar(identifier: .gregorian).dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        let time = UInt16((components.hour ?? 0) << 11 | (components.minute ?? 0) << 5 | (components.second ?? 0) / 2)
        let day = UInt16(max(0, (components.year ?? 1980) - 1980) << 9 | (components.month ?? 1) << 5 | (components.day ?? 1))
        return (time, day)
    }

    private static func u16(_ bytes: [UInt8], _ offset: Int) -> UInt16 {
        UInt16(bytes[offset]) | UInt16(bytes[offset + 1]) << 8
    }

    private static func u32(_ bytes: [UInt8], _ offset: Int) -> UInt32 {
        UInt32(bytes[offset]) | UInt32(bytes[offset + 1]) << 8 | UInt32(bytes[offset + 2]) << 16 | UInt32(bytes[offset + 3]) << 24
    }
}

nonisolated enum CRC32 {
    private static let table: [UInt32] = (0..<256).map { value -> UInt32 in
        var crc = UInt32(value)
        for _ in 0..<8 { crc = crc & 1 == 1 ? 0xedb8_8320 ^ (crc >> 1) : crc >> 1 }
        return crc
    }

    static func checksum(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xffff_ffff
        data.withUnsafeBytes { buffer in
            for byte in buffer { crc = table[Int((crc ^ UInt32(byte)) & 0xff)] ^ (crc >> 8) }
        }
        return crc ^ 0xffff_ffff
    }
}

nonisolated extension Data {
    mutating func appendLE16(_ value: UInt16) {
        append(UInt8(value & 0xff))
        append(UInt8(value >> 8))
    }

    mutating func appendLE32(_ value: UInt32) {
        for shift in stride(from: 0, to: 32, by: 8) { append(UInt8((value >> UInt32(shift)) & 0xff)) }
    }

    mutating func appendBE32(_ value: UInt32) {
        for shift in stride(from: 24, through: 0, by: -8) { append(UInt8((value >> UInt32(shift)) & 0xff)) }
    }
}
