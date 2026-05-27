package com.astarworks.astera.core.application.service.validation

import com.astarworks.astera.core.application.port.outbound.validation.ContentReferenceCheck
import com.astarworks.astera.core.domain.validation.ContentValidationError
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BootValidateContentLifecycleListenerTest {

    @Test
    fun `lenient onReady logs without throwing on critical`() {
        val validator = ContentValidatorService()
        validator.register(
            ContentReferenceCheck {
                listOf(
                    ContentValidationError.MissingReference(
                        sourceId = "shop:s",
                        referenceKind = "item",
                        referencedId = "items:x",
                        field = "offers[0]",
                    ),
                )
            },
        )
        val listener = BootValidateContentLifecycleListener(
            validator,
            mode = BootValidateContentLifecycleListener.Mode.Lenient,
        )
        listener.onReady() // must not throw
    }

    @Test
    fun `strict onReady aborts on critical`() {
        val validator = ContentValidatorService()
        validator.register(
            ContentReferenceCheck {
                listOf(
                    ContentValidationError.MissingReference(
                        sourceId = "shop:s",
                        referenceKind = "item",
                        referencedId = "items:x",
                        field = "offers[0]",
                    ),
                )
            },
        )
        val listener = BootValidateContentLifecycleListener(
            validator,
            mode = BootValidateContentLifecycleListener.Mode.Strict,
        )
        assertThatThrownBy { listener.onReady() }
            .isInstanceOf(BootValidateContentLifecycleListener.ContentValidationStrictFailure::class.java)
            .extracting { (it as BootValidateContentLifecycleListener.ContentValidationStrictFailure).criticalCount }
            .isEqualTo(1)
    }

    @Test
    fun `strict onReady succeeds when only warnings present`() {
        val validator = ContentValidatorService()
        validator.register(
            ContentReferenceCheck {
                listOf(ContentValidationError.DuplicateId(sourceId = "x", registryName = "y"))
            },
        )
        val listener = BootValidateContentLifecycleListener(
            validator,
            mode = BootValidateContentLifecycleListener.Mode.Strict,
        )
        listener.onReady() // critical is empty, must not throw
    }

    @Test
    fun `priority is high so listener fires early`() {
        val listener = BootValidateContentLifecycleListener(ContentValidatorService())
        assertThat(listener.priority).isGreaterThan(0)
    }
}
