package org.openwrench.frontend.android

import kotlinx.serialization.json.*
import org.openwrench.core.*

/** Bounded policy proof; containing segment supplies generation, source ECU and receipt timeline. */
internal object GuidedDiagnosisArchive {
    fun validate(p: GuidedDiagnosisProgress) {
        require(p.policyVersion == GuidedDiagnosisPolicy.VERSION)
        require(p.evaluatedAtOffsetMillis in 0..DiagnosisCodec.MAX_ELAPSED)
        require(p.banks.map { it.bank } == listOf(1, 2))
        p.banks.forEach { bank ->
            DiagnosisCodec.plainText(bank.requirement, 2_000)
            require((bank.status == GuidedBankStatus.QUALIFIED) == (bank.interval != null))
            bank.interval?.let {
                FuelCorrectionArchive.validate(listOf(it), bank.bank, p.evaluatedAtOffsetMillis)
                require(it.contextBounds.coolant.minimum.value >= GuidedDiagnosisPolicy.MIN_COOLANT_CELSIUS)
            }
        }
        val first = p.banks.mapNotNull { it.interval?.lastOffsetMillis }.minOrNull()
        require(p.initialAssessmentOffsetMillis == first.takeIf { p.scanSucceeded })
    }

    fun encode(p: GuidedDiagnosisProgress): JsonElement {
        validate(p)
        return buildJsonObject {
            put("policy", p.policyVersion); put("scan_succeeded", p.scanSucceeded)
            put("evaluated_offset_ms", p.evaluatedAtOffsetMillis)
            put("initial_assessment_offset_ms", p.initialAssessmentOffsetMillis?.let(::JsonPrimitive) ?: JsonNull)
            put("banks", JsonArray(p.banks.map { bank -> buildJsonObject {
                put("bank", bank.bank); put("status", bank.status.name); put("requirement", bank.requirement)
                put("interval", bank.interval?.let(FuelCorrectionArchive::encode) ?: JsonNull)
            } }))
        }
    }

    fun decode(value: JsonElement): GuidedDiagnosisProgress {
        val p = value.jsonObject
        require(p.keys == setOf("policy", "scan_succeeded", "evaluated_offset_ms", "initial_assessment_offset_ms", "banks"))
        fun number(key: String) = p.getValue(key).jsonPrimitive.also { require(!it.isString) }.long
        val banks = p.getValue("banks").jsonArray.also { require(it.size == 2) }.map { valueBank ->
            val b = valueBank.jsonObject
            require(b.keys == setOf("bank", "status", "requirement", "interval"))
            fun text(key: String) = b.getValue(key).jsonPrimitive.also { require(it.isString) }.content
            GuidedBankProgress(b.getValue("bank").jsonPrimitive.also { require(!it.isString) }.int,
                GuidedBankStatus.valueOf(text("status")), text("requirement"),
                b.getValue("interval").takeUnless { it == JsonNull }?.let(FuelCorrectionArchive::decode))
        }
        return GuidedDiagnosisProgress(number("policy").also { require(it in 1..Int.MAX_VALUE) }.toInt(),
            p.getValue("scan_succeeded").jsonPrimitive.also { require(!it.isString) }.boolean,
            number("evaluated_offset_ms"), banks,
            p.getValue("initial_assessment_offset_ms").takeUnless { it == JsonNull }?.let { number("initial_assessment_offset_ms") }).also(::validate)
    }
}
