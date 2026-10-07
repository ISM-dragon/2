package com.example.domain.property

import com.example.data.local.entity.PropertyEntity
import kotlin.math.abs

/**
 * Converts between the Room entity (which the UI reads) and the canonical domain model (which the
 * ingestion pipeline works with).
 */
object PropertyMapper {

    fun toCanonical(entity: PropertyEntity): CanonicalProperty {
        val (zip5, plus4) = UsPropertyNormalizer.splitZip(entity.zipCode)
        val halfBaths = if (entity.halfBathrooms > 0) {
            entity.halfBathrooms
        } else {
            // legacy rows store a total such as 2.5; recover the half bath count without touching
            // `bathrooms` so the screens keep showing the same number.
            val fractional = entity.bathrooms - entity.bathrooms.toInt()
            if (abs(fractional - 0.5) < 0.01) 1 else 0
        }
        return CanonicalProperty(
            propertyId = entity.id,
            address = CanonicalAddress(
                streetAddress = entity.address,
                unit = UsPropertyNormalizer.normalizeUnit(entity.unitNumber),
                city = entity.city,
                stateCode = UsPropertyNormalizer.normalizeStateCode(entity.state),
                zip5 = zip5,
                zipPlus4 = plus4,
                county = entity.county,
                countyFips = UsPropertyNormalizer.normalizeCountyFips(entity.countyFips, entity.state),
                latitude = entity.latitude,
                longitude = entity.longitude
            ),
            apn = entity.apn,
            mlsNumber = entity.mlsNumber,
            listingStatus = UsPropertyNormalizer.listingStatus(entity.status),
            propertyType = UsPropertyNormalizer.propertyType(
                entity.propertySubType.ifBlank { entity.propertyType }
            ),
            subType = entity.propertySubType,
            bedrooms = entity.bedrooms,
            fullBathrooms = (entity.bathrooms.toInt()).coerceAtLeast(0),
            halfBathrooms = halfBaths,
            livingAreaSqFt = entity.squareFeet,
            lotSizeSqFt = entity.lotSizeSqFt,
            yearBuilt = entity.yearBuilt,
            stories = entity.stories,
            garageSpaces = entity.garageSpaces,
            hasPool = entity.hasPool,
            hoaMonthly = entity.hoaMonthly,
            listPrice = entity.price,
            sourceId = entity.primarySourceId,
            sourceExternalId = entity.id,
            canonicalKey = entity.canonicalKey,
            listingStatusUpdatedAt = entity.listingStatusUpdatedAt,
            lastVerifiedAt = entity.lastVerifiedAt
        )
    }

    /**
     * Canonicalizes a raw entity: fills the normalized identity columns without changing any of the
     * legacy/UI columns. Safe to call repeatedly (idempotent).
     */
    fun canonicalized(entity: PropertyEntity): PropertyEntity {
        val address = UsPropertyNormalizer.address(
            streetAddress = entity.address,
            unit = entity.unitNumber,
            city = entity.city,
            stateCode = entity.state,
            zipCode = entity.zipCode,
            county = entity.county,
            countyFips = entity.countyFips,
            latitude = entity.latitude,
            longitude = entity.longitude
        )
        val totalBathrooms = entity.bathrooms
        val fullBaths = totalBathrooms.toInt().coerceAtLeast(0)
        val halfBaths = if (entity.halfBathrooms > 0) {
            entity.halfBathrooms
        } else {
            if (abs(totalBathrooms - fullBaths - 0.5) < 0.01) 1 else 0
        }
        return entity.copy(
            unitNumber = address.unit,
            normalizedAddress = address.singleLine,
            canonicalKey = entity.canonicalKey ?: UsPropertyNormalizer.canonicalKey(address),
            county = address.county,
            countyFips = address.countyFips,
            apn = UsPropertyNormalizer.normalizeApn(entity.apn),
            mlsNumber = UsPropertyNormalizer.normalizeMlsNumber(entity.mlsNumber),
            propertySubType = entity.propertySubType.ifBlank {
                UsPropertyNormalizer.propertyType(entity.propertyType).name
            },
            halfBathrooms = halfBaths,
            lastVerifiedAt = if (entity.lastVerifiedAt == 0L) entity.scannedAt else entity.lastVerifiedAt
        )
    }

    /** Writes a canonical property back onto an entity, preserving non canonical UI columns. */
    fun applyTo(entity: PropertyEntity, property: CanonicalProperty): PropertyEntity = entity.copy(
        address = property.address.streetAddress.ifBlank { entity.address },
        unitNumber = property.address.unit,
        normalizedAddress = property.address.singleLine,
        city = property.address.city.ifBlank { entity.city },
        state = property.address.stateCode.ifBlank { entity.state },
        zipCode = property.address.zip5.ifBlank { entity.zipCode },
        county = property.address.county,
        countyFips = property.address.countyFips,
        latitude = property.address.latitude.takeIf { it != 0.0 } ?: entity.latitude,
        longitude = property.address.longitude.takeIf { it != 0.0 } ?: entity.longitude,
        apn = property.apn,
        mlsNumber = property.mlsNumber,
        propertySubType = property.subType.ifBlank { property.propertyType.name },
        bedrooms = property.bedrooms,
        bathrooms = property.totalBathrooms,
        halfBathrooms = property.halfBathrooms,
        squareFeet = property.livingAreaSqFt,
        lotSizeSqFt = property.lotSizeSqFt,
        yearBuilt = property.yearBuilt,
        stories = property.stories,
        garageSpaces = property.garageSpaces,
        hasPool = property.hasPool,
        hoaMonthly = property.hoaMonthly,
        price = property.listPrice,
        status = property.listingStatus.name,
        canonicalKey = property.canonicalKey ?: entity.canonicalKey,
        primarySourceId = property.sourceId ?: entity.primarySourceId,
        listingStatusUpdatedAt = maxOf(entity.listingStatusUpdatedAt, property.listingStatusUpdatedAt),
        lastVerifiedAt = maxOf(entity.lastVerifiedAt, property.lastVerifiedAt)
    )

    fun toDedupInput(entity: PropertyEntity): DedupInput {
        val canonical = toCanonical(entity)
        return toDedupInput(canonical)
    }

    fun toDedupInput(property: CanonicalProperty): DedupInput = DedupInput(
        propertyId = property.propertyId,
        canonicalKey = property.canonicalKey,
        normalizedAddress = if (property.address.streetAddress.isBlank()) {
            property.address.singleLine
        } else {
            property.address.streetAddress
        },
        unit = property.address.unit,
        city = UsPropertyNormalizer.normalizeCity(property.address.city),
        stateCode = UsPropertyNormalizer.normalizeStateCode(property.address.stateCode),
        zip5 = UsPropertyNormalizer.splitZip(property.address.zip5).first,
        apn = property.apn,
        mlsNumber = property.mlsNumber,
        latitude = property.address.latitude,
        longitude = property.address.longitude,
        bedrooms = property.bedrooms,
        livingAreaSqFt = property.livingAreaSqFt,
        propertyType = property.propertyType
    )
}
