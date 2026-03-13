package one.wabbit.random.gen

import kotlinx.serialization.Serializable

@Serializable data class BitSpan(val startBits: ULong, val lengthBits: ULong)

sealed interface TapeAnnotation {
    data class ReadBool(val ix: ULong) : TapeAnnotation

    data class ReadInt(val span: BitSpan, val range: kotlin.ranges.IntRange) : TapeAnnotation

    data class ReadUInt(val span: BitSpan, val range: kotlin.ranges.UIntRange) : TapeAnnotation

    data class Double(val span: BitSpan, val eps: kotlin.Double) : TapeAnnotation

    data class LabelStart(val id: ULong, val bitIndex: ULong, val label: String) : TapeAnnotation

    data class LabelEnd(val id: ULong, val bitIndex: ULong) : TapeAnnotation

    data class ChoiceStart(val bitIndex: ULong, val draw: ReadUInt, val id: ULong) : TapeAnnotation

    data class ChoiceEnd(val endIndex: ULong, val id: ULong) : TapeAnnotation

    data class ListStart(val id: ULong, val bitIndex: ULong) : TapeAnnotation

    data class ListElement(val id: ULong, val bitIndex: ULong) : TapeAnnotation

    data class ListEnd(val id: ULong, val bitIndex: ULong) : TapeAnnotation
}

sealed interface TapeData {
    data class Bool(val value: kotlin.Boolean) : TapeData

    data class Int(val value: kotlin.Int) : TapeData

    data class UInt(val value: kotlin.UInt) : TapeData

    data class Double(val bits: Int, val value: kotlin.Double) : TapeData

    data class Label(val label: String, val value: TapeData) : TapeData

    data class Choice(val draw: UInt, val value: TapeData) : TapeData

    data class List(val length: Int, val elements: kotlin.collections.List<TapeData>) : TapeData
}
