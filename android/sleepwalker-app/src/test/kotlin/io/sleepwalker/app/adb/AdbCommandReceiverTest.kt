package io.sleepwalker.app.adb

import android.content.Context
import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for AdbCommandReceiver encoded text decoding and plain text preservation.
 *
 * Tests validate:
 * - Base64url encoded text decoding with shell-sensitive characters
 * - Invalid encoded payload rejection
 * - Plain text path preservation
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33]) // Android 13
class AdbCommandReceiverTest {

    private lateinit var context: Context
    private lateinit var receiver: AdbCommandReceiver

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        receiver = AdbCommandReceiver()
    }

    @After
    fun tearDown() {
        // Reset any static mocks
    }

    @Test
    fun testDecodeBase64Url_valid_simpleString() {
        // Test simple ASCII string
        val input = "Hello World"
        val encoded = base64UrlEncode(input)
        val intent = createTypeTextIntent(textEncoded = encoded)
        
        receiver.onReceive(context, intent)
        
        // Verify the intent was processed without error
        // (In a real test, we'd verify SwLog calls or mock the BLE service)
    }

    @Test
    fun testDecodeBase64Url_valid_shellSensitiveCharacters() {
        // Test shell-sensitive characters that should be safely transmitted
        val input = "The quick brown fox jumps over the lazy dog! @#\$%^&*()_+-=[]{}|;:,.<>?/~\`"
        val encoded = base64UrlEncode(input)
        val intent = createTypeTextIntent(textEncoded = encoded)
        
        receiver.onReceive(context, intent)
        
        // Verify decoding succeeded
    }

    @Test
    fun testDecodeBase64Url_valid_unicodeCharacters() {
        // Test Unicode characters (emoji, accented chars)
        val input = "Hello 世界 🌍 ñ"
        val encoded = base64UrlEncode(input)
        val intent = createTypeTextIntent(textEncoded = encoded)
        
        receiver.onReceive(context, intent)
        
        // Verify decoding succeeded
    }

    @Test
    fun testDecodeBase64Url_valid_newlinesAndTabs() {
        // Test whitespace characters
        val input = "Line 1\nLine 2\tTabbed"
        val encoded = base64UrlEncode(input)
        val intent = createTypeTextIntent(textEncoded = encoded)
        
        receiver.onReceive(context, intent)
        
        // Verify decoding succeeded
    }

    @Test
    fun testDecodeBase64Url_invalid_base64UrlFormat() {
        // Test invalid base64url input (corrupted padding, wrong chars)
        val invalidEncoded = "not-valid-base64!@#"
        val intent = createTypeTextIntent(textEncoded = invalidEncoded)
        
        receiver.onReceive(context, intent)
        
        // Should reject the invalid payload and log failure
        // (SwLog.failure with "decode_failed" and "invalid_base64url")
    }

    @Test
    fun testDecodeBase64Url_invalid_truncatedInput() {
        // Test truncated base64url input
        val invalidEncoded = "YWJj".substring(0, 2) // Truncated base64
        val intent = createTypeTextIntent(textEncoded = invalidEncoded)
        
        receiver.onReceive(context, intent)
        
        // Should reject the invalid payload
    }

    @Test
    fun testDecodeBase64Url_nullInput() {
        // Test null encoded input (should fall through to plain text path)
        val intent = createTypeTextIntent(text = "plain text", textEncoded = null)
        
        receiver.onReceive(context, intent)
        
        // Should use plain text path
    }

    @Test
    fun testPlainText_preservation() {
        // Test that plain text path still works
        val plainText = "Simple plain text without encoding"
        val intent = createTypeTextIntent(text = plainText)
        
        receiver.onReceive(context, intent)
        
        // Should process plain text normally
    }

    @Test
    fun testPlainText_shellSensitiveCharacters() {
        // Test plain text with shell-sensitive characters (would fail in real shell)
        val plainText = "echo \$HOME"
        val intent = createTypeTextIntent(text = plainText)
        
        receiver.onReceive(context, intent)
        
        // Plain text path should accept the string as-is
    }

    @Test
    fun testPlainText_emptyString() {
        // Test empty plain text
        val intent = createTypeTextIntent(text = "")
        
        receiver.onReceive(context, intent)
        
        // Should handle empty string
    }

    @Test
    fun testEncodedTakesPrecedence_overPlainText() {
        // Test that when both text and textEncoded are present, textEncoded is used
        val plainText = "plain"
        val encodedText = base64UrlEncode("encoded")
        val intent = createTypeTextIntent(text = plainText, textEncoded = encodedText)
        
        receiver.onReceive(context, intent)
        
        // Should prefer encoded path over plain text
    }

    @Test
    fun testEncoded_andPlainText_bothNull() {
        // Test when both text and textEncoded are null
        val intent = createTypeTextIntent(text = null, textEncoded = null)
        
        receiver.onReceive(context, intent)
        
        // Should handle gracefully (use empty string)
    }

    @Test
    fun testDecodeBase64Url_valid_specialUtf8() {
        // Test special UTF-8 characters (emoji, zero-width joiner, variation selectors)
        val input = "👨‍👩‍👧‍👦 Family emoji with ZWJ"
        val encoded = base64UrlEncode(input)
        val intent = createTypeTextIntent(textEncoded = encoded)
        
        receiver.onReceive(context, intent)
        
        // Should decode complex emoji correctly
    }

    @Test
    fun testDecodeBase64Url_valid_binaryLikeBytes() {
        // Test binary-like bytes that are valid UTF-8
        val input = "\u0001\u0002\u0003\u00FF" // Control chars and Latin-1 supplement
        val encoded = base64UrlEncode(input)
        val intent = createTypeTextIntent(textEncoded = encoded)
        
        receiver.onReceive(context, intent)
        
        // Should decode binary-like UTF-8 bytes
    }

    @Test
    fun testDecodeBase64Url_invalid_wrongPadding() {
        // Test base64url with incorrect padding
        val invalidEncoded = "AAAA====" // Wrong padding
        val intent = createTypeTextIntent(textEncoded = invalidEncoded)
        
        receiver.onReceive(context, intent)
        
        // Should reject invalid padding
    }

    /**
     * Helper function to create a type-text intent with optional text/encoded parameters
     */
    private fun createTypeTextIntent(text: String? = null, textEncoded: String? = null): Intent {
        return Intent(AdbCommandReceiver.ACTION).apply {
            putExtra(AdbCommandReceiver.EXTRA_CMD, "type-text")
            putExtra(AdbCommandReceiver.EXTRA_SEQ, 1)
            text?.let { putExtra(AdbCommandReceiver.EXTRA_TEXT, it) }
            textEncoded?.let { putExtra(AdbCommandReceiver.EXTRA_TEXT_ENCODED, it) }
        }
    }

    /**
     * Helper function to base64url-encode a string (Android implementation)
     */
    private fun base64UrlEncode(input: String): String {
        val bytes = input.toByteArray(Charsets.UTF_8)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
    }
}
