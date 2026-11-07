#!/usr/bin/env swift
import AVFoundation
import Foundation
import VideoToolbox

guard CommandLine.arguments.count >= 3 else {
    fputs("Usage: optimize_video.swift <input.mp4> <output.mp4> [bitrate_mbps=5.5] [hevc|h264]\n", stderr)
    exit(1)
}

let inputURL = URL(fileURLWithPath: CommandLine.arguments[1])
let outputURL = URL(fileURLWithPath: CommandLine.arguments[2])
let mbps = CommandLine.arguments.count >= 4 ? (Double(CommandLine.arguments[3]) ?? 5.5) : 5.5
let useHEVC = (CommandLine.arguments.count >= 5 ? CommandLine.arguments[4].lowercased() : "h264") == "hevc"

try? FileManager.default.removeItem(at: outputURL)

let asset = AVURLAsset(url: inputURL)
guard let videoTrack = asset.tracks(withMediaType: .video).first else {
    fputs("No video track found in \(inputURL.path)\n", stderr)
    exit(1)
}

let naturalSize = videoTrack.naturalSize.applying(videoTrack.preferredTransform)
let srcWidth = abs(naturalSize.width)
let srcHeight = abs(naturalSize.height)
let targetWidth = min(Int(srcWidth), 1920)
let targetHeight = min(Int(srcHeight), 1080)
let fps = max(Double(videoTrack.nominalFrameRate), 30.0)
let bitRate = Int(mbps * 1_000_000)

let reader = try! AVAssetReader(asset: asset)
let readerSettings: [String: Any] = [
    kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
    kCVPixelBufferWidthKey as String: targetWidth,
    kCVPixelBufferHeightKey as String: targetHeight
]
let readerOutput = AVAssetReaderTrackOutput(track: videoTrack, outputSettings: readerSettings)
readerOutput.alwaysCopiesSampleData = false
reader.add(readerOutput)

let writer = try! AVAssetWriter(outputURL: outputURL, fileType: .mp4)
var compressionProps: [String: Any] = [
    AVVideoAverageBitRateKey: bitRate,
    AVVideoExpectedSourceFrameRateKey: fps,
    AVVideoMaxKeyFrameIntervalKey: Int(fps * 2)
]
if !useHEVC {
    compressionProps[AVVideoProfileLevelKey] = AVVideoProfileLevelH264HighAutoLevel
}

let writerSettings: [String: Any] = [
    AVVideoCodecKey: useHEVC ? AVVideoCodecType.hevc : AVVideoCodecType.h264,
    AVVideoWidthKey: targetWidth,
    AVVideoHeightKey: targetHeight,
    AVVideoCompressionPropertiesKey: compressionProps
]

let writerInput = AVAssetWriterInput(mediaType: .video, outputSettings: writerSettings)
writerInput.expectsMediaDataInRealTime = false
writer.add(writerInput)

reader.startReading()
writer.startWriting()
writer.startSession(atSourceTime: .zero)

let sema = DispatchSemaphore(value: 0)
let queue = DispatchQueue(label: "video.transcode")

writerInput.requestMediaDataWhenReady(on: queue) {
    while writerInput.isReadyForMoreMediaData {
        if let sample = readerOutput.copyNextSampleBuffer() {
            writerInput.append(sample)
        } else {
            writerInput.markAsFinished()
            writer.finishWriting {
                sema.signal()
            }
            break
        }
    }
}

sema.wait()
if writer.status == .completed {
    print("Optimized \(targetWidth)x\(targetHeight) @ \(mbps) Mbps (\(useHEVC ? "HEVC" : "H.264")) -> \(outputURL.path)")
} else {
    fputs("Failed: \(String(describing: writer.error))\n", stderr)
    exit(1)
}
