package com.example.urlintelligence.model

/**
 * Every discrete piece of information the URL-intelligence layer can resolve for a property.
 *
 * Fields are the unit of provenance: whatever is written into a [PropertyDraft] is later
 * traceable back to the source, URL, extractor and extraction method that produced it.
 */
enum class PropertyField(val stableName: String) {
    ADDRESS_LINE1("address.line1"),
    UNIT("address.unit"),
    CITY("address.city"),
    STATE("address.state"),
    POSTAL_CODE("address.postal_code"),
    COUNTRY("address.country"),
    LATITUDE("geo.latitude"),
    LONGITUDE("geo.longitude"),
    PROPERTY_TYPE("property.type"),
    LISTING_STATUS("listing.status"),
    LIST_PRICE("listing.price"),
    BEDROOMS("property.bedrooms"),
    BATHROOMS("property.bathrooms"),
    LIVING_AREA_SQFT("property.living_area_sqft"),
    LOT_SIZE_SQFT("property.lot_size_sqft"),
    YEAR_BUILT("property.year_built"),
    DESCRIPTION("listing.description"),
    PRIMARY_IMAGE_URL("media.primary_image"),
    IMAGE_URLS("media.images"),
    ESTIMATED_MONTHLY_RENT("valuation.rent_monthly"),
    ANNUAL_TAX_AMOUNT("valuation.tax_annual"),
    HOA_MONTHLY_FEE("valuation.hoa_monthly"),
    DAYS_ON_MARKET("listing.days_on_market"),
    LISTED_AT_EPOCH_MILLIS("listing.listed_at"),
    MLS_ID("identifiers.mls"),
    PARCEL_ID("identifiers.parcel");

    companion object {
        /**
         * Minimum information required before a resolved property is considered
         * "complete". Anything missing here yields a partial (but still usable) result.
         */
        val REQUIRED_FIELDS: Set<PropertyField> =
            setOf(ADDRESS_LINE1, CITY, STATE, POSTAL_CODE)

        /** Fields that improve confidence/analytics but never block an import. */
        val OPTIONAL_FIELDS: Set<PropertyField> =
            values().toSet() - REQUIRED_FIELDS

        fun fromStableName(name: String): PropertyField? =
            values().firstOrNull { it.stableName.equals(name, ignoreCase = true) }
    }
}
