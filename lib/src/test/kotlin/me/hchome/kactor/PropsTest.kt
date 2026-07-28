package me.hchome.kactor

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PropsTest {

    @Test
    fun `of with no pairs returns EMPTY`() {
        assertSame(Props.EMPTY, Props.of())
    }

    @Test
    fun `of with empty map returns EMPTY`() {
        assertSame(Props.EMPTY, Props.of(emptyMap()))
    }

    @Test
    fun `get returns the value stored under a name`() {
        val props = Props.of("prefix" to "hi", "count" to 3)
        assertEquals("hi", props.get<String>("prefix"))
        assertEquals(3, props.get<Int>("count"))
    }

    @Test
    fun `of with a map behaves the same as of with pairs`() {
        val props = Props.of(mapOf("prefix" to "hi"))
        assertEquals("hi", props.get<String>("prefix"))
    }

    @Test
    fun `getOrNull returns null for a missing name`() {
        val props = Props.of("prefix" to "hi")
        assertNull(props.getOrNull<String>("missing"))
    }

    @Test
    fun `getOrNull returns null when the stored value has a different type`() {
        val props = Props.of("count" to 3)
        assertNull(props.getOrNull<String>("count"))
    }

    @Test
    fun `get throws for a missing name`() {
        val props = Props.of("prefix" to "hi")
        assertFailsWith<NoSuchElementException> { props.get<String>("missing") }
    }

    @Test
    fun `get throws when the stored value has a different type`() {
        val props = Props.of("count" to 3)
        assertFailsWith<NoSuchElementException> { props.get<String>("count") }
    }

    @Test
    fun `contains reflects presence regardless of type`() {
        val props = Props.of("prefix" to "hi")
        assertTrue("prefix" in props)
        assertFalse("missing" in props)
    }
}