package com.example.urlintelligence

import com.example.urlintelligence.html.Html
import com.example.urlintelligence.html.StructuredData
import com.example.urlintelligence.json.JsonException
import com.example.urlintelligence.json.JsonParser
import com.example.urlintelligence.json.JsonValue
import com.example.urlintelligence.json.collectObjects
import com.example.urlintelligence.json.deepString
import com.example.urlintelligence.json.findObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonParserTest {

    @Test
    fun `parses scalars objects and arrays`() {
        val value = JsonParser.parse(
            """
            {
              "string": "hello",
              "number": 42,
              "decimal": 2.5,
              "negative": -7,
              "bool": true,
              "null": null,
              "array": [1, "two", false],
              "nested": { "a": { "b": "c" } }
            }
            """.trimIndent()
        )
        val obj = value as JsonValue.JsonObject
        assertEquals("hello", obj.string("string"))
        assertEquals(42, obj.int("number"))
        assertEquals(2.5, obj.double("decimal")!!, 0.001)
        assertEquals(-7.0, obj.double("negative")!!, 0.001)
        assertEquals(true, obj.bool("bool"))
        assertTrue(obj["null"] is JsonValue.JsonNull)
        assertEquals(3, obj.array("array")!!.items.size)
        assertEquals("c", obj.obj("nested")!!.obj("a")!!.string("b"))
    }

    @Test
    fun `decodes escape sequences`() {
        val input = "{\"a\":\"line\\nbreak\\ttab \\\"q\\\" \\\\ \\u00e9 end\"}"
        val value = JsonParser.parse(input)
        val text = (value as JsonValue.JsonObject).string("a")
        assertEquals("line\nbreak\ttab \"q\" \\ \u00e9 end", text)
    }

    @Test
    fun `numbers stored as strings are readable as doubles`() {
        val value = JsonParser.parse("""{"price":"485000","sqft":"2250"}""") as JsonValue.JsonObject
        assertEquals(485000.0, value.doubleOrNumericString("price")!!, 0.001)
        assertEquals(2250.0, value.doubleOrNumericString("sqft")!!, 0.001)
        assertNull(value.int("sqft"))
    }

    @Test
    fun `reads at type as string or array`() {
        val single = JsonParser.parse("""{"@type":"SingleFamilyResidence"}""") as JsonValue.JsonObject
        assertEquals(listOf("SingleFamilyResidence"), single.types())

        val multi = JsonParser.parse("""{"@type":["Product","Residence"]}""") as JsonValue.JsonObject
        assertEquals(listOf("Product", "Residence"), multi.types())
    }

    @Test
    fun `firstString and firstDouble fall back across keys`() {
        val value = JsonParser.parse("""{"lowPrice":"400000","yearBuilt":2017}""") as JsonValue.JsonObject
        assertEquals("400000", value.firstString("price", "lowPrice"))
        assertEquals(2017.0, value.firstDouble("yearBuilt", "built")!!, 0.001)
    }

    @Test
    fun `malformed json degrades instead of throwing`() {
        assertNull(JsonParser.parseOrNull("""{"a":}"""))
        assertNull(JsonParser.parseOrNull("not json at all"))
        var thrown = false
        try {
            JsonParser.parse("""{"a":}""")
        } catch (t: JsonException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun `object graph helpers walk nested nodes`() {
        val root = JsonParser.parse(
            """{"@graph":[{"@type":"Residence","address":{"streetAddress":"1 Main St"}}]}"""
        )
        val objects = root.collectObjects()
        assertTrue(objects.size >= 3)
        assertEquals("1 Main St", root.deepString("streetAddress"))
        val residence = root.findObject { it.types().contains("Residence") }
        assertTrue(residence != null)
    }
}

class HtmlHelperTest {

    @Test
    fun `decodes named and numeric entities`() {
        assertEquals("A & B", Html.decodeEntities("A &amp; B"))
        assertEquals("2,250 sqft", Html.decodeEntities("2,250&nbsp;sqft"))
        assertEquals("It's 3 bd", Html.decodeEntities("It&#39;s 3 bd"))
        assertEquals("Café", Html.decodeEntities("Caf&#233;"))
    }

    @Test
    fun `reads meta tags by name or property`() {
        val html = """
            <html><head>
            <meta name="description" content="A great home">
            <meta property="og:title" content="1 Main St, Austin, TX 78704">
            <meta property="og:image" content="https://img.test/a.jpg"/>
            </head><body></body></html>
        """.trimIndent()
        assertEquals("A great home", Html.metaContent(html, "description"))
        assertEquals("1 Main St, Austin, TX 78704", Html.metaContent(html, "og:title"))
        assertEquals("https://img.test/a.jpg", Html.metaContent(html, "og:image"))
        assertNull(Html.metaContent(html, "og:missing"))
    }

    @Test
    fun `reads attributes and data-testid blocks`() {
        assertEquals("a b", Html.attribute("data-x=\"a b\" other=1", "data-x"))
        assertEquals("list-price", Html.attribute("data-testid='list-price'", "data-testid"))
        val html = """<div data-testid="list-price"><span>$485,000</span></div>"""
        assertEquals("$485,000", Html.dataTestIdText(html, "list-price"))
    }

    @Test
    fun `extracts json ld blocks including graph documents`() {
        val html = """
            <script type="application/ld+json">{"@type":"Residence","name":"A"}</script>
            <script type="application/ld+json">{"@graph":[{"@type":"Product"}]}</script>
            <script>var notJsonLd = 1;</script>
        """.trimIndent()
        val objects = StructuredData.objects(html)
        assertTrue(objects.any { it.types().contains("Residence") })
        assertTrue(objects.any { it.types().contains("Product") })
    }

    @Test
    fun `ranks the most property like node first`() {
        val html = """
            <script type="application/ld+json">
            [
              {"@type":"WebPage","name":"page"},
              {"@type":"SingleFamilyResidence","address":{"streetAddress":"1 Main St"},"offers":{"price":"100"},"floorSize":{"value":1000}}
            ]
            </script>
        """.trimIndent()
        val best = StructuredData.propertyLike(StructuredData.objects(html))
        assertTrue(best != null)
        assertTrue(best!!.types().contains("SingleFamilyResidence"))
    }

    @Test
    fun `malformed json ld blocks are reported and skipped`() {
        val malformed = mutableListOf<String>()
        val html = """<script type="application/ld+json">{"broken":}</script>"""
        val objects = StructuredData.objects(html, malformed)
        assertTrue(objects.isEmpty())
        assertEquals(1, malformed.size)
    }

    @Test
    fun `strips scripts and tags to readable text`() {
        val html = """<html><body><script>var x = 1;</script><h1>Hello&nbsp;world</h1></body></html>"""
        val text = Html.text(html)
        assertTrue(text.contains("Hello"))
        assertTrue(text.contains("world"))
        assertFalse(text.contains("var x"))
    }
}
