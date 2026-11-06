import Foundation

/// BLAKE2b (RFC 7693), unkeyed, with any output length from 1 to 64 bytes.
nonisolated struct Blake2b {
    private static let iv: [UInt64] = [
        0x6a09_e667_f3bc_c908, 0xbb67_ae85_84ca_a73b, 0x3c6e_f372_fe94_f82b, 0xa54f_f53a_5f1d_36f1,
        0x510e_527f_ade6_82d1, 0x9b05_688c_2b3e_6c1f, 0x1f83_d9ab_fb41_bd6b, 0x5be0_cd19_137e_2179,
    ]

    private static let sigma: [[Int]] = [
        [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15],
        [14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3],
        [11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4],
        [7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8],
        [9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13],
        [2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9],
        [12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11],
        [13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10],
        [6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5],
        [10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0],
    ]

    private var h: [UInt64]
    private var buffer = [UInt8]()
    private var counter: UInt64 = 0
    private let outputLength: Int

    init(outputLength: Int) {
        precondition((1...64).contains(outputLength))
        self.outputLength = outputLength
        h = Blake2b.iv
        h[0] ^= 0x0101_0000 ^ UInt64(outputLength)
    }

    mutating func update(_ bytes: [UInt8]) {
        for byte in bytes {
            if buffer.count == 128 {
                counter += 128
                compress(block: buffer, isLast: false)
                buffer.removeAll(keepingCapacity: true)
            }
            buffer.append(byte)
        }
    }

    mutating func update(_ data: Data) { update([UInt8](data)) }

    mutating func update(le32 value: UInt32) {
        update([UInt8(value & 0xff), UInt8((value >> 8) & 0xff), UInt8((value >> 16) & 0xff), UInt8(value >> 24)])
    }

    mutating func finalize() -> [UInt8] {
        counter += UInt64(buffer.count)
        let block = buffer + [UInt8](repeating: 0, count: 128 - buffer.count)
        compress(block: block, isLast: true)
        var output = [UInt8]()
        output.reserveCapacity(64)
        for word in h {
            for shift in stride(from: 0, to: 64, by: 8) { output.append(UInt8((word >> UInt64(shift)) & 0xff)) }
        }
        return Array(output.prefix(outputLength))
    }

    static func hash(_ bytes: [UInt8], outputLength: Int) -> [UInt8] {
        var hasher = Blake2b(outputLength: outputLength)
        hasher.update(bytes)
        return hasher.finalize()
    }

    private mutating func compress(block: [UInt8], isLast: Bool) {
        var m = [UInt64](repeating: 0, count: 16)
        for i in 0..<16 {
            var word: UInt64 = 0
            for j in 0..<8 { word |= UInt64(block[i * 8 + j]) << UInt64(8 * j) }
            m[i] = word
        }
        var v = h + Blake2b.iv
        v[12] ^= counter
        if isLast { v[14] = ~v[14] }
        func g(_ a: Int, _ b: Int, _ c: Int, _ d: Int, _ x: UInt64, _ y: UInt64) {
            v[a] = v[a] &+ v[b] &+ x
            v[d] = rotr(v[d] ^ v[a], 32)
            v[c] = v[c] &+ v[d]
            v[b] = rotr(v[b] ^ v[c], 24)
            v[a] = v[a] &+ v[b] &+ y
            v[d] = rotr(v[d] ^ v[a], 16)
            v[c] = v[c] &+ v[d]
            v[b] = rotr(v[b] ^ v[c], 63)
        }
        for round in 0..<12 {
            let s = Blake2b.sigma[round % 10]
            g(0, 4, 8, 12, m[s[0]], m[s[1]])
            g(1, 5, 9, 13, m[s[2]], m[s[3]])
            g(2, 6, 10, 14, m[s[4]], m[s[5]])
            g(3, 7, 11, 15, m[s[6]], m[s[7]])
            g(0, 5, 10, 15, m[s[8]], m[s[9]])
            g(1, 6, 11, 12, m[s[10]], m[s[11]])
            g(2, 7, 8, 13, m[s[12]], m[s[13]])
            g(3, 4, 9, 14, m[s[14]], m[s[15]])
        }
        for i in 0..<8 { h[i] ^= v[i] ^ v[i + 8] }
    }
}

@inline(__always)
nonisolated private func rotr(_ x: UInt64, _ n: UInt64) -> UInt64 { (x >> n) | (x << (64 - n)) }

/// Argon2id version 1.3 (RFC 9106).
nonisolated public enum Argon2id {
    private static let blockWords = 128
    private static let syncPoints = 4

    /// Derives `length` bytes.
    /// - Parameters:
    ///   - memoryKiB: memory size in KiB (Bitwarden stores MiB; multiply by 1024).
    public static func hash(
        password: Data,
        salt: Data,
        iterations: Int,
        memoryKiB: Int,
        parallelism: Int,
        length: Int = 32,
        secret: Data = Data(),
        associatedData: Data = Data()
    ) -> Data {
        precondition(iterations >= 1 && parallelism >= 1 && memoryKiB >= 8 * parallelism && length >= 4)

        // H0
        var h0Hasher = Blake2b(outputLength: 64)
        h0Hasher.update(le32: UInt32(parallelism))
        h0Hasher.update(le32: UInt32(length))
        h0Hasher.update(le32: UInt32(memoryKiB))
        h0Hasher.update(le32: UInt32(iterations))
        h0Hasher.update(le32: 0x13)
        h0Hasher.update(le32: 2) // Argon2id
        h0Hasher.update(le32: UInt32(password.count)); h0Hasher.update(password)
        h0Hasher.update(le32: UInt32(salt.count)); h0Hasher.update(salt)
        h0Hasher.update(le32: UInt32(secret.count)); h0Hasher.update(secret)
        h0Hasher.update(le32: UInt32(associatedData.count)); h0Hasher.update(associatedData)
        let h0 = h0Hasher.finalize()

        let segmentLength = memoryKiB / (syncPoints * parallelism)
        let laneLength = segmentLength * syncPoints
        let blockCount = laneLength * parallelism

        let memory = UnsafeMutablePointer<UInt64>.allocate(capacity: blockCount * blockWords)
        memory.initialize(repeating: 0, count: blockCount * blockWords)
        defer {
            memory.update(repeating: 0, count: blockCount * blockWords)
            memory.deallocate()
        }

        // First two blocks of each lane.
        for lane in 0..<parallelism {
            for column in 0..<2 {
                let bytes = variableHash(h0 + le32(UInt32(column)) + le32(UInt32(lane)), length: 1024)
                let block = memory + (lane * laneLength + column) * blockWords
                for i in 0..<blockWords {
                    var word: UInt64 = 0
                    for j in 0..<8 { word |= UInt64(bytes[i * 8 + j]) << UInt64(8 * j) }
                    block[i] = word
                }
            }
        }

        let context = Context(
            memory: memory,
            laneLength: laneLength,
            segmentLength: segmentLength,
            lanes: parallelism,
            blockCount: blockCount,
            passes: iterations
        )
        for pass in 0..<iterations {
            for slice in 0..<syncPoints {
                DispatchQueue.concurrentPerform(iterations: parallelism) { lane in
                    fillSegment(context: context, pass: pass, lane: lane, slice: slice)
                }
            }
        }

        // Final block: XOR of the last block of every lane.
        var final = [UInt64](repeating: 0, count: blockWords)
        for lane in 0..<parallelism {
            let block = memory + (lane * laneLength + laneLength - 1) * blockWords
            for i in 0..<blockWords { final[i] ^= block[i] }
        }
        var finalBytes = [UInt8]()
        finalBytes.reserveCapacity(1024)
        for word in final {
            for shift in stride(from: 0, to: 64, by: 8) { finalBytes.append(UInt8((word >> UInt64(shift)) & 0xff)) }
        }
        return Data(variableHash(finalBytes, length: length))
    }

    private struct Context: @unchecked Sendable {
        let memory: UnsafeMutablePointer<UInt64>
        let laneLength: Int
        let segmentLength: Int
        let lanes: Int
        let blockCount: Int
        let passes: Int
    }

    private static func le32(_ value: UInt32) -> [UInt8] {
        [UInt8(value & 0xff), UInt8((value >> 8) & 0xff), UInt8((value >> 16) & 0xff), UInt8(value >> 24)]
    }

    /// H' from RFC 9106 section 3.3.
    private static func variableHash(_ input: [UInt8], length: Int) -> [UInt8] {
        if length <= 64 {
            var hasher = Blake2b(outputLength: length)
            hasher.update(le32: UInt32(length))
            hasher.update(input)
            return hasher.finalize()
        }
        var output = [UInt8]()
        output.reserveCapacity(length)
        var hasher = Blake2b(outputLength: 64)
        hasher.update(le32: UInt32(length))
        hasher.update(input)
        var v = hasher.finalize()
        let r = (length + 31) / 32 - 2
        output.append(contentsOf: v.prefix(32))
        for _ in 1..<r {
            v = Blake2b.hash(v, outputLength: 64)
            output.append(contentsOf: v.prefix(32))
        }
        v = Blake2b.hash(v, outputLength: length - 32 * r)
        output.append(contentsOf: v)
        return output
    }

    private static func fillSegment(context: Context, pass: Int, lane: Int, slice: Int) {
        let memory = context.memory
        let isDataIndependent = pass == 0 && slice < syncPoints / 2

        let scratch = UnsafeMutablePointer<UInt64>.allocate(capacity: blockWords * 6)
        scratch.initialize(repeating: 0, count: blockWords * 6)
        defer { scratch.deallocate() }
        let addressBlock = scratch
        let inputBlock = scratch + blockWords
        let zeroBlock = scratch + blockWords * 2
        let temp = scratch + blockWords * 3
        let work = scratch + blockWords * 4

        func nextAddresses() {
            inputBlock[6] &+= 1
            compress(previous: zeroBlock, reference: inputBlock, output: temp, xorInto: false, work: work)
            compress(previous: zeroBlock, reference: temp, output: addressBlock, xorInto: false, work: work)
        }

        if isDataIndependent {
            inputBlock[0] = UInt64(pass)
            inputBlock[1] = UInt64(lane)
            inputBlock[2] = UInt64(slice)
            inputBlock[3] = UInt64(context.blockCount)
            inputBlock[4] = UInt64(context.passes)
            inputBlock[5] = 2
        }

        var startingIndex = 0
        if pass == 0 && slice == 0 {
            startingIndex = 2
            if isDataIndependent { nextAddresses() }
        }

        var currentOffset = lane * context.laneLength + slice * context.segmentLength + startingIndex
        var previousOffset = currentOffset % context.laneLength == 0
            ? currentOffset + context.laneLength - 1
            : currentOffset - 1

        for index in startingIndex..<context.segmentLength {
            if currentOffset % context.laneLength == 1 { previousOffset = currentOffset - 1 }

            let pseudoRandom: UInt64
            if isDataIndependent {
                if index % blockWords == 0 { nextAddresses() }
                pseudoRandom = addressBlock[index % blockWords]
            } else {
                pseudoRandom = memory[previousOffset * blockWords]
            }

            var referenceLane = Int((pseudoRandom >> 32) % UInt64(context.lanes))
            if pass == 0 && slice == 0 { referenceLane = lane }
            let isSameLane = referenceLane == lane

            let referenceAreaSize: Int
            if pass == 0 {
                if slice == 0 {
                    referenceAreaSize = index - 1
                } else if isSameLane {
                    referenceAreaSize = slice * context.segmentLength + index - 1
                } else {
                    referenceAreaSize = slice * context.segmentLength + (index == 0 ? -1 : 0)
                }
            } else if isSameLane {
                referenceAreaSize = context.laneLength - context.segmentLength + index - 1
            } else {
                referenceAreaSize = context.laneLength - context.segmentLength + (index == 0 ? -1 : 0)
            }

            var relativePosition = pseudoRandom & 0xffff_ffff
            relativePosition = (relativePosition &* relativePosition) >> 32
            relativePosition = UInt64(referenceAreaSize) - 1 - ((UInt64(referenceAreaSize) &* relativePosition) >> 32)

            let startPosition = pass == 0 ? 0 : (slice == syncPoints - 1 ? 0 : (slice + 1) * context.segmentLength)
            let referenceIndex = (startPosition + Int(relativePosition)) % context.laneLength
            let referenceOffset = referenceLane * context.laneLength + referenceIndex

            compress(
                previous: memory + previousOffset * blockWords,
                reference: memory + referenceOffset * blockWords,
                output: memory + currentOffset * blockWords,
                xorInto: pass != 0,
                work: work
            )
            currentOffset += 1
            previousOffset += 1
        }
    }

    /// The compression function G. Writes G(previous, reference) to `output`, XORed with the old
    /// content of `output` when `xorInto` is set (passes after the first). `work` must have room
    /// for 256 words.
    private static func compress(
        previous: UnsafeMutablePointer<UInt64>,
        reference: UnsafeMutablePointer<UInt64>,
        output: UnsafeMutablePointer<UInt64>,
        xorInto: Bool,
        work: UnsafeMutablePointer<UInt64>
    ) {
        let rp = work
        let zp = work + blockWords
        for i in 0..<blockWords {
            let value = previous[i] ^ reference[i]
            rp[i] = value
            zp[i] = value
        }
        // Rows: 8 rows of 16 consecutive words.
        for row in 0..<8 {
            let b = row * 16
            permute(zp, b, b + 1, b + 2, b + 3, b + 4, b + 5, b + 6, b + 7,
                    b + 8, b + 9, b + 10, b + 11, b + 12, b + 13, b + 14, b + 15)
        }
        // Columns: column j holds words 2j and 2j+1 of every row.
        for column in 0..<8 {
            let c = column * 2
            permute(zp, c, c + 1, c + 16, c + 17, c + 32, c + 33, c + 48, c + 49,
                    c + 64, c + 65, c + 80, c + 81, c + 96, c + 97, c + 112, c + 113)
        }
        if xorInto {
            for i in 0..<blockWords { output[i] ^= zp[i] ^ rp[i] }
        } else {
            for i in 0..<blockWords { output[i] = zp[i] ^ rp[i] }
        }
    }

    @inline(__always)
    private static func gb(_ v: UnsafeMutablePointer<UInt64>, _ a: Int, _ b: Int, _ c: Int, _ d: Int) {
        v[a] = v[a] &+ v[b] &+ 2 &* (v[a] & 0xffff_ffff) &* (v[b] & 0xffff_ffff)
        v[d] = rotr(v[d] ^ v[a], 32)
        v[c] = v[c] &+ v[d] &+ 2 &* (v[c] & 0xffff_ffff) &* (v[d] & 0xffff_ffff)
        v[b] = rotr(v[b] ^ v[c], 24)
        v[a] = v[a] &+ v[b] &+ 2 &* (v[a] & 0xffff_ffff) &* (v[b] & 0xffff_ffff)
        v[d] = rotr(v[d] ^ v[a], 16)
        v[c] = v[c] &+ v[d] &+ 2 &* (v[c] & 0xffff_ffff) &* (v[d] & 0xffff_ffff)
        v[b] = rotr(v[b] ^ v[c], 63)
    }

    // swiftlint:disable:next function_parameter_count
    @inline(__always)
    private static func permute(
        _ v: UnsafeMutablePointer<UInt64>,
        _ i0: Int, _ i1: Int, _ i2: Int, _ i3: Int, _ i4: Int, _ i5: Int, _ i6: Int, _ i7: Int,
        _ i8: Int, _ i9: Int, _ i10: Int, _ i11: Int, _ i12: Int, _ i13: Int, _ i14: Int, _ i15: Int
    ) {
        gb(v, i0, i4, i8, i12)
        gb(v, i1, i5, i9, i13)
        gb(v, i2, i6, i10, i14)
        gb(v, i3, i7, i11, i15)
        gb(v, i0, i5, i10, i15)
        gb(v, i1, i6, i11, i12)
        gb(v, i2, i7, i8, i13)
        gb(v, i3, i4, i9, i14)
    }
}
