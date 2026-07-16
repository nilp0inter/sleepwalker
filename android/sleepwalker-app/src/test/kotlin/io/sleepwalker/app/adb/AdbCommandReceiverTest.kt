package io.sleepwalker.app.adb

import android.util.Base64
import io.sleepwalker.core.editor.EditorResult
import java.util.Collections
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AdbCommandReceiverTest {

    @Test
    fun strict_set_text_decoder_preserves_shell_sensitive_utf8_snapshot_exactly_once() {
        val snapshot = "space \"double\" 'single' \\ backslash; pipe| ampersand& dollar\$ parens() redirect<> bang! caret^ percent% equals= bracket[] brace{} tilde~ backtick`"
        val encoded = encodeBase64Url(snapshot)

        val decoded = AdbCommandReceiver.decodeBase64UrlStrict(encoded)

        assertEquals(snapshot, decoded)
        val invokedWith = mutableListOf<String>()
        val result = AdbCommandReceiver().decodeAndExecuteSetText(
            textEncoded = encoded,
            plainText = "must-not-win",
            seq = 901,
            lock = Any(),
            setTextBlock = { text ->
                invokedWith += text
                EditorResult.Synced(text, emptyList())
            },
        )

        assertEquals(listOf(snapshot), invokedWith)
        assertEquals(901, result.seq)
    }

    @Test
    fun strict_set_text_decoder_accepts_urlsafe_padding_forms_and_rejects_noncanonical_syntax() {
        assertEquals("\u07ff", AdbCommandReceiver.decodeBase64UrlStrict("37-"))
        assertEquals("\u07ff", AdbCommandReceiver.decodeBase64UrlStrict("37-="))
        assertEquals("\u083f", AdbCommandReceiver.decodeBase64UrlStrict("4KC_"))

        val invalidPayloads = listOf(
            "37+", // Standard Base64 alphabet is not base64url.
            "4KC/", // Standard Base64 alphabet is not base64url.
            "37- ", // Whitespace is not part of the encoded payload.
            "37-\n", // Whitespace is not part of the encoded payload.
            "3=7-", // Padding may only be the final suffix.
            "YQ=", // Padded form must have a canonical four-byte length.
            "37-==", // Padding count/length is noncanonical.
            "A", // Length remainder one cannot encode bytes.
        )
        for (payload in invalidPayloads) {
            assertEquals(null, AdbCommandReceiver.decodeBase64UrlStrict(payload))
        }
    }

    @Test
    fun invalid_set_text_payloads_return_structured_failure_without_hid_execution_while_legacy_decoder_remains_available() {
        val malformedUtf8 = Base64.encodeToString(
            byteArrayOf(0xC3.toByte(), 0x28),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        val legacyText = "legacy append \\ path"
        val invokedWith = mutableListOf<String>()
        val receiver = AdbCommandReceiver()

        val invalidBase64 = receiver.decodeAndExecuteSetText(
            textEncoded = "invalid%base64",
            plainText = "must-not-run",
            seq = 906,
            lock = Any(),
            setTextBlock = { text ->
                invokedWith += text
                EditorResult.Synced(text, emptyList())
            },
        )
        val malformedEncoding = receiver.decodeAndExecuteSetText(
            textEncoded = malformedUtf8,
            plainText = "must-not-run",
            seq = 907,
            lock = Any(),
            setTextBlock = { text ->
                invokedWith += text
                EditorResult.Synced(text, emptyList())
            },
        )

        assertEquals(emptyList<String>(), invokedWith)
        assertEquals("EditorFailure", invalidBase64.editorResult)
        assertEquals("planning", invalidBase64.failureClass)
        assertEquals("not_sent", invalidBase64.transportStatus)
        assertEquals("invalid_base64url_or_utf8", invalidBase64.failure)
        assertEquals(0, invalidBase64.planOps)
        assertEquals(906, invalidBase64.seq)
        assertEquals("invalid_base64url_or_utf8", malformedEncoding.failure)
        assertEquals(907, malformedEncoding.seq)

        val invalidJson = org.json.JSONObject(receiver.setTextDiagnosticJson(invalidBase64))
        assertFalse(invalidJson.getBoolean("ok"))
        assertEquals("set-text", invalidJson.getString("cmd"))
        assertEquals("planning", invalidJson.getString("failure_class"))
        assertEquals("invalid_base64url_or_utf8", invalidJson.getString("failure"))
        assertEquals("not_sent", invalidJson.getString("transport_status"))
        assertEquals(0, invalidJson.getJSONArray("operation_seqs").length())
        assertEquals(legacyText, AdbCommandReceiver.decodeBase64Url(encodeBase64Url(legacyText)))
        assertEquals("\uFFFD(", AdbCommandReceiver.decodeBase64Url(malformedUtf8))
    }

    @Test
    fun set_text_diagnostic_fields_carry_correlated_snapshot_plan_and_prediction_evidence() {
        val result = SetTextResult(
            seq = 902,
            textLength = 13,
            editorResult = "Synced",
            planOps = 3,
            operationSeqs = listOf(40, 41, 42),
            failureClass = null,
            transportStatus = "ok",
            abiVersion = 1,
            targetId = "readline-emacs-ascii",
            lcp = 4,
            oldMid = "old",
            newMid = "new",
            predictedBuffer = "done snapshot",
            predictedPoint = 13,
            predictedRevision = 7,
            assumedBuffer = "old snapshot",
            planOpNames = listOf("key_down", "key_up", "key_tap"),
            operationStatuses = listOf("40:sent_to_usb", "41:sent_to_usb", "42:sent_to_usb"),
            failure = null,
            targetVersion = "1.2.3",
        )

        val fields = AdbCommandReceiver().setTextDiagnosticFields(result)

        assertEquals(902, fields["seq"])
        assertEquals(13, fields["text_length"])
        assertEquals("Synced", fields["result"])
        assertEquals(3, fields["plan_ops"])
        assertEquals("40,41,42", fields["operation_seqs"])
        assertEquals(1, fields["abi_version"])
        assertEquals("readline-emacs-ascii", fields["target_id"])
        assertEquals(4, fields["lcp"])
        assertEquals("old", fields["old_mid"])
        assertEquals("new", fields["new_mid"])
        assertEquals("done snapshot", fields["predicted_buffer"])
        assertEquals(13, fields["predicted_point"])
        assertEquals(7L, fields["predicted_revision"])
        assertEquals("old snapshot", fields["assumed_buffer"])
        assertFalse(fields.containsKey("failure_class"))

        val json = org.json.JSONObject(AdbCommandReceiver().setTextDiagnosticJson(result))
        assertEquals(902, json.getInt("seq"))
        assertEquals("set-text", json.getString("cmd"))
        assertTrue(json.getBoolean("ok"))
        assertEquals(13, json.getInt("decoded_len"))
        assertEquals("readline-emacs-ascii", json.getJSONObject("package").getString("id"))
        assertEquals("1.2.3", json.getJSONObject("package").getString("version"))
        assertEquals(1, json.getJSONObject("package").getInt("host_abi"))
        assertEquals("new", json.getString("new_mid"))
        assertEquals("done snapshot", json.getJSONObject("predicted").getString("buffer"))
        assertEquals("key_up", json.getJSONArray("plan_ops").getString(1))
        assertEquals(41, json.getJSONArray("operation_seqs").getInt(1))
        assertEquals("41:sent_to_usb", json.getJSONArray("operation_statuses").getString(1))
    }

    @Test
    fun set_text_diagnostic_fields_preserve_transport_failure_class_and_partial_plan_evidence() {
        val result = SetTextResult(
            seq = 903,
            textLength = 5,
            editorResult = "EditorFailure",
            planOps = 2,
            operationSeqs = listOf(70, 71),
            failureClass = "transport",
            transportStatus = "transport: Timeout waiting for ack (seq 71)",
            abiVersion = 1,
            targetId = "readline-emacs-ascii",
            lcp = 2,
            oldMid = "ld",
            newMid = "new",
            predictedBuffer = "new",
            predictedPoint = 3,
            predictedRevision = 8,
            assumedBuffer = "old",
            planOpNames = listOf("key_down", "key_up"),
            operationStatuses = listOf("70:sent_to_usb", "71:timeout"),
            failure = "TransportFailure(reason=Timeout waiting for ack (seq 71))",
            targetVersion = "1.2.3",
        )

        val fields = AdbCommandReceiver().setTextDiagnosticFields(result)

        assertEquals("EditorFailure", fields["result"])
        assertEquals("transport", fields["failure_class"])
        assertEquals("transport: Timeout waiting for ack (seq 71)", fields["transport_status"])
        assertEquals(2, fields["plan_ops"])
        assertEquals("70,71", fields["operation_seqs"])
        assertEquals("readline-emacs-ascii", fields["target_id"])
    }

    @Test
    fun concurrent_set_text_calls_enter_editor_in_arrival_order_without_coalescing_or_interleaving() {
        val lock = Any()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondSubmitted = CountDownLatch(1)
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val outcomes = Collections.synchronizedList(mutableListOf<SetTextResult>())
        val receiver = AdbCommandReceiver()
        val setTextBlock: (String) -> EditorResult = { text ->
            calls += "start:$text"
            if (text == "first") {
                firstEntered.countDown()
                releaseFirst.await()
            }
            calls += "finish:$text"
            EditorResult.Synced(text, emptyList())
        }

        val first = Thread {
            outcomes += receiver.executeSetText(
                text = "first",
                seq = 904,
                lock = lock,
                resultDecorator = { result ->
                    calls += "status:first"
                    result.copy(
                        operationSeqs = listOf(904),
                        operationStatuses = listOf("904:sent_to_usb"),
                    )
                },
                setTextBlock = setTextBlock,
            )
        }
        val second = Thread {
            secondSubmitted.countDown()
            outcomes += receiver.executeSetText(
                text = "second",
                seq = 905,
                lock = lock,
                resultDecorator = { result ->
                    calls += "status:second"
                    result.copy(
                        operationSeqs = listOf(905),
                        operationStatuses = listOf("905:sent_to_usb"),
                    )
                },
                setTextBlock = setTextBlock,
            )
        }
        first.start()
        firstEntered.await()
        second.start()
        secondSubmitted.await()

        try {
            while (second.state == Thread.State.RUNNABLE) {
                Thread.onSpinWait()
            }
            assertEquals(Thread.State.BLOCKED, second.state)
            assertEquals(listOf("start:first"), calls.toList())
        } finally {
            releaseFirst.countDown()
            first.join()
            second.join()
        }

        assertEquals(
            listOf(
                "start:first",
                "finish:first",
                "status:first",
                "start:second",
                "finish:second",
                "status:second",
            ),
            calls.toList(),
        )
        assertEquals(listOf(904, 905), outcomes.map { it.seq })
        assertEquals(listOf(904), outcomes[0].operationSeqs)
        assertEquals(listOf("904:sent_to_usb"), outcomes[0].operationStatuses)
        assertEquals(listOf(905), outcomes[1].operationSeqs)
        assertEquals(listOf("905:sent_to_usb"), outcomes[1].operationStatuses)
    }

    private fun encodeBase64Url(text: String): String = Base64.encodeToString(
        text.toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )
}
