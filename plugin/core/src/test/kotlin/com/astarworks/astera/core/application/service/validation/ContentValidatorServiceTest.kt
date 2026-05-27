package com.astarworks.astera.core.application.service.validation

import com.astarworks.astera.core.application.port.outbound.validation.ContentReferenceCheck
import com.astarworks.astera.core.domain.validation.ContentValidationError
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ContentValidatorServiceTest {

    @Test
    fun `validateAll on empty validator returns empty report`() {
        val s = ContentValidatorService()
        val r = s.validateAll()
        assertThat(r.hasAny).isFalse()
        assertThat(r.registriesChecked).isZero()
    }

    @Test
    fun `validateAll routes MissingReference to critical bucket`() {
        val s = ContentValidatorService()
        s.register(
            ContentReferenceCheck {
                listOf(
                    ContentValidationError.MissingReference(
                        sourceId = "shop:starter",
                        referenceKind = "item",
                        referencedId = "items:missing-sword",
                        field = "offers[0].itemId",
                    ),
                )
            },
        )
        val r = s.validateAll()
        assertThat(r.critical).hasSize(1)
        assertThat(r.warning).isEmpty()
        assertThat(r.hasCritical).isTrue()
        assertThat(r.critical[0].message).contains("shop:starter").contains("items:missing-sword")
    }

    @Test
    fun `validateAll routes DuplicateId to warning bucket`() {
        val s = ContentValidatorService()
        s.register(
            ContentReferenceCheck {
                listOf(ContentValidationError.DuplicateId(sourceId = "x", registryName = "items"))
            },
        )
        val r = s.validateAll()
        assertThat(r.warning).hasSize(1)
        assertThat(r.critical).isEmpty()
        assertThat(r.hasCritical).isFalse()
    }

    @Test
    fun `validateAll routes InvalidSchema to critical bucket`() {
        val s = ContentValidatorService()
        s.register(
            ContentReferenceCheck {
                listOf(ContentValidationError.InvalidSchema(sourceId = "x", reason = "bad"))
            },
        )
        val r = s.validateAll()
        assertThat(r.critical).hasSize(1)
    }

    @Test
    fun `validateAll keeps other checks running when one throws`() {
        val s = ContentValidatorService()
        s.register(ContentReferenceCheck { error("boom") })
        s.register(
            ContentReferenceCheck {
                listOf(ContentValidationError.DuplicateId(sourceId = "y", registryName = "z"))
            },
        )
        val r = s.validateAll()
        assertThat(r.critical).hasSize(1).first().extracting { it.message }
            .asString().contains("threw")
        assertThat(r.warning).hasSize(1)
        assertThat(r.registriesChecked).isEqualTo(2)
    }

    @Test
    fun `register returns cancellable Registration`() {
        val s = ContentValidatorService()
        val reg = s.register(
            ContentReferenceCheck {
                listOf(ContentValidationError.DuplicateId(sourceId = "x", registryName = "y"))
            },
        )
        assertThat(s.registeredCount()).isEqualTo(1)
        reg.unregister()
        assertThat(s.registeredCount()).isZero()
        assertThat(s.validateAll().hasAny).isFalse()
    }

    @Test
    fun `register accepts multiple checks and aggregates`() {
        val s = ContentValidatorService()
        repeat(3) { i ->
            s.register(
                ContentReferenceCheck {
                    listOf(
                        ContentValidationError.MissingReference(
                            sourceId = "src$i",
                            referenceKind = "item",
                            referencedId = "ref$i",
                            field = "f",
                        ),
                    )
                },
            )
        }
        val r = s.validateAll()
        assertThat(r.critical).hasSize(3)
        assertThat(r.registriesChecked).isEqualTo(3)
    }
}
