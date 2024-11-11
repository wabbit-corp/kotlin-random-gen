package one.wabbit.random.gen

import kotlin.test.*

class MutableBitListSpec {
    private fun <A> serializeDeserialize(arr: A): A {
        val bytes = java.io.ByteArrayOutputStream()
        val stream = java.io.ObjectOutputStream(bytes)
        stream.writeObject(arr)
        stream.close()

        val stream2 = java.io.ObjectInputStream(java.io.ByteArrayInputStream(bytes.toByteArray()))
        @Suppress("UNCHECKED_CAST")
        return stream2.readObject() as A
    }

    @Test
    fun test() {
        val buf = MutableBitDeque()
        // assertEquals(buf, serializeDeserialize(buf))
        assertEquals("MutableBitDeque(\"\")", buf.toString())
        buf.add(true)
        assertEquals("MutableBitDeque(\"1\")", buf.toString())
        buf.add(false)
        assertEquals("MutableBitDeque(\"10\")", buf.toString())
        buf.addAll(0xF1.toByte(), BitOrder.LSB_FIRST)
        assertEquals("MutableBitDeque(\"1010001111\")", buf.toString())
        assertEquals(5L, buf.removeFirst(4, BitOrder.LSB_FIRST))
        assertEquals(6L, buf.size)
        // b001111 = 15
        assertEquals(15, buf.removeFirst(6, BitOrder.MSB_FIRST))
    }
}
