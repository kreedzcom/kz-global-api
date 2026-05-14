package kz.global.api.storage

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.DeleteObjectRequest
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.sdk.kotlin.services.s3.presigners.presignGetObject
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.net.url.Url
import kz.global.api.config.R2Config
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.hours

open class R2Client(
    private val config: R2Config,
    internal val client: S3Client = buildS3Client(config),
) {
    private val log = LoggerFactory.getLogger(R2Client::class.java)

    companion object {
        fun buildS3Client(config: R2Config): S3Client = S3Client {
            region = "auto"
            endpointUrl = Url.parse(config.endpoint)
            credentialsProvider = aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider {
                accessKeyId = config.accessKeyId
                secretAccessKey = config.secretAccessKey
            }
        }
    }

    open suspend fun put(key: String, bytes: ByteArray) {
        log.debug("Uploading {} bytes to R2 key: {}", bytes.size, key)
        client.putObject(PutObjectRequest {
            bucket = config.bucket
            this.key = key
            body = ByteStream.fromBytes(bytes)
        })
    }

    open suspend fun presignedGetUrl(key: String): String {
        val request = GetObjectRequest {
            bucket = config.bucket
            this.key = key
        }
        return client.presignGetObject(request, 1.hours).url.toString()
    }

    open suspend fun delete(key: String) {
        log.debug("Deleting R2 key: {}", key)
        client.deleteObject(DeleteObjectRequest {
            bucket = config.bucket
            this.key = key
        })
    }
}
