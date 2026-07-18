package io.sleepwalker.app.adb

import android.util.Base64
import io.sleepwalker.core.editor.EditorResult
import io.sleepwalker.core.editor.AbiValue
import io.sleepwalker.core.editor.SymbolicAction
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
    fun invalid_set_text_payloads_return_structured_failure_without_hid_execution() {
        val malformedUtf8 = Base64.encodeToString(
            byteArrayOf(0xC3.toByte(), 0x28),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
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
    }

    @Test
    fun set_text_diagnostics_serialize_complete_pure_abi_evidence() {
        val opaqueInput = AbiValue.Obj(mapOf(
            "mode" to AbiValue.Str("insert"),
            "position" to AbiValue.Int64(4),
        ))
        val opaqueOutput = AbiValue.Obj(mapOf(
            "mode" to AbiValue.Str("command"),
            "position" to AbiValue.Int64(9),
        ))
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
            targetVersion = "2.0.0",
            targetSourceHash = "sha256:readline",
            currentText = "old snapshot",
            desiredText = "done snapshot",
            opaqueInputState = opaqueInput,
            opaqueOutputState = opaqueOutput,
            symbolicActions = listOf(
                SymbolicAction.Tap("USB_KEY_A"),
                SymbolicAction.Text("done snapshot"),
            ),
            compiledOperations = listOf("tap USB_KEY_A", "text done snapshot"),
            layoutId = "us-qwerty:2026-07",
            costMetricId = "low-level-ops:v1",
            policyId = "CONFORMANCE",
            transactionOutcome = "COMMITTED",
            classification = null,
            planOpNames = listOf("key_tap", "text"),
            operationStatuses = listOf("40:sent_to_usb", "41:sent_to_usb"),
            failure = null,
        )

        val fields = AdbCommandReceiver().setTextDiagnosticFields(result)
        val json = org.json.JSONObject(AdbCommandReceiver().setTextDiagnosticJson(result))

        assertEquals("old snapshot", fields["current_text"])
        assertEquals("done snapshot", fields["desired_text"])
        assertEquals("readline-emacs-ascii", fields["package_id"])
        assertEquals("sha256:readline", fields["package_source_hash"])
        assertEquals("CONFORMANCE", fields["policy_id"])
        assertEquals("COMMITTED", fields["transaction_outcome"])
        assertTrue(json.getJSONObject("opaque_input_state").has("position"))
        assertEquals("command", json.getJSONObject("opaque_output_state").getString("mode"))
        assertEquals("tap", json.getJSONArray("symbolic_actions").getJSONObject(0).getString("kind"))
        assertEquals("text done snapshot", json.getJSONArray("compiled_operations").getString(1))
        assertEquals("us-qwerty:2026-07", json.getString("layout_id"))
        assertEquals("low-level-ops:v1", json.getString("cost_metric_id"))
    }

    @Test
    fun failed_delivery_retains_unknown_transaction_outcome_and_abi_evidence() {
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
            targetVersion = "2.0.0",
            targetSourceHash = "sha256:readline",
            currentText = "old",
            desiredText = "new",
            opaqueInputState = AbiValue.Obj(mapOf("epoch" to AbiValue.Int64(7))),
            opaqueOutputState = null,
            symbolicActions = listOf(SymbolicAction.Text("new")),
            compiledOperations = listOf("text new"),
            layoutId = "us-qwerty:2026-07",
            costMetricId = "low-level-ops:v1",
            policyId = "CONFORMANCE",
            transactionOutcome = "UNKNOWN",
            classification = "TransportFailure",
            planOpNames = listOf("text"),
            operationStatuses = listOf("70:sent_to_usb", "71:timeout"),
            failure = "TransportFailure(reason=Timeout waiting for ack (seq 71))",
        )

        val json = org.json.JSONObject(AdbCommandReceiver().setTextDiagnosticJson(result))

        assertFalse(json.getBoolean("ok"))
        assertEquals("transport", json.getString("failure_class"))
        assertEquals("UNKNOWN", json.getString("transaction_outcome"))
        assertEquals("TransportFailure", json.getString("classification"))
        assertEquals("old", json.getString("current_text"))
        assertEquals("new", json.getString("desired_text"))
        assertEquals("text", json.getJSONArray("symbolic_actions").getJSONObject(0).getString("kind"))
        assertEquals("text new", json.getJSONArray("compiled_operations").getString(0))
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
