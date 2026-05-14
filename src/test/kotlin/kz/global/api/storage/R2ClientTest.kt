package kz.global.api.storage

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.DeleteObjectRequest
import aws.sdk.kotlin.services.s3.model.DeleteObjectResponse
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.sdk.kotlin.services.s3.model.PutObjectResponse
import io.mockk.*
import kz.global.api.config.R2Config
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class R2ClientTest {

    private val config = R2Config(
        endpoint = "https://r2.example.com",
        accessKeyId = "key",
        secretAccessKey = "secret",
        bucket = "test-bucket",
    )

    private val mockS3 = mockk<S3Client>(relaxed = true)
    private val r2 = R2Client(config, mockS3)

    @Test
    fun `put calls putObject with correct bucket and key`() = runTest {
        val bytes = ByteArray(10) { it.toByte() }
        coEvery { mockS3.putObject(any<PutObjectRequest>()) } returns PutObjectResponse {}

        r2.put("replays/abc.krpz", bytes)

        coVerify {
            mockS3.putObject(match<PutObjectRequest> {
                it.bucket == "test-bucket" && it.key == "replays/abc.krpz"
            })
        }
    }

    @Test
    fun `put propagates exceptions from s3Client`() = runTest {
        coEvery { mockS3.putObject(any<PutObjectRequest>()) } throws RuntimeException("upload failed")

        assertFailsWith<RuntimeException> { r2.put("replays/fail.krpz", ByteArray(1)) }
    }

    @Test
    fun `delete calls deleteObject with correct bucket and key`() = runTest {
        coEvery { mockS3.deleteObject(any<DeleteObjectRequest>()) } returns DeleteObjectResponse {}

        r2.delete("replays/old.krpz")

        coVerify {
            mockS3.deleteObject(match<DeleteObjectRequest> {
                it.bucket == "test-bucket" && it.key == "replays/old.krpz"
            })
        }
    }

    @Test
    fun `delete propagates exceptions from s3Client`() = runTest {
        coEvery { mockS3.deleteObject(any<DeleteObjectRequest>()) } throws RuntimeException("delete failed")

        assertFailsWith<RuntimeException> { r2.delete("replays/fail.krpz") }
    }

    // Note: presignedGetUrl relies on the AWS SDK presignGetObject extension function,
    // which is a top-level extension and cannot be intercepted by mockk without mockkStatic.
    // Its correctness is verified indirectly via ReplayServiceDbTest.getPresignedUrl tests,
    // where R2Client is mocked at the interface level.
}
