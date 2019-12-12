package net.corda.serialization.internal.amqp

import net.corda.core.KeepForDJVM
import net.corda.core.serialization.SerializationContext
import net.corda.core.serialization.SerializedBytes
import net.corda.core.utilities.contextLogger
import net.corda.serialization.internal.CordaSerializationEncoding
import net.corda.serialization.internal.SectionId
import net.corda.serialization.internal.SerializationContextImpl
import net.corda.serialization.internal.byteArrayOutput
import net.corda.serialization.internal.model.TypeIdentifier
import org.apache.qpid.proton.codec.Data
import java.io.NotSerializableException
import java.io.OutputStream
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import java.nio.ByteBuffer
import java.util.*
import kotlin.collections.LinkedHashSet

@KeepForDJVM
data class BytesAndSchemas<T : Any>(
        val obj: SerializedBytes<T>,
        val schema: Schema,
        val transformsSchema: TransformsSchema)

/**
 * Main entry point for serializing an object to AMQP.
 *
 * @param serializerFactory This is the factory for [AMQPSerializer] instances and can be shared across multiple
 * instances and threads.
 */
@KeepForDJVM
open class SerializationOutput constructor(
        internal val serializerFactory: LocalSerializerFactory
) {
    companion object {
        private val logger = contextLogger()

        var optimized = true
    }

    private val objectHistory: MutableMap<Any, Int> = IdentityHashMap()
    private val serializerHistory: MutableSet<AMQPSerializer<*>> = LinkedHashSet()
    internal val schemaHistory: MutableSet<TypeNotation> = LinkedHashSet()

    /**
     * Serialize the given object to AMQP, wrapped in our [Envelope] wrapper which carries an AMQP 1.0 schema, and prefixed
     * with a header to indicate that this is serialized with AMQP and not Kryo, and what version of the Corda implementation
     * of AMQP serialization constructed the serialized form.
     */
    @Throws(NotSerializableException::class)
    fun <T : Any> serialize(obj: T, context: SerializationContext): SerializedBytes<T> {
        try {
            return _serialize(obj, context)
        } catch (amqp: AMQPNotSerializableException) {
            amqp.log("Serialize", logger)
            throw NotSerializableException(amqp.mitigation)
        } finally {
            andFinally()
        }
    }

    // NOTE: No need to handle AMQPNotSerializableExceptions here as this is an internal
    // only / testing function and it doesn't matter if they escape
    @Throws(NotSerializableException::class)
    fun <T : Any> serializeAndReturnSchema(obj: T, context: SerializationContext): BytesAndSchemas<T> {
        try {
            val blob = _serialize(obj, context)
            val schema = Schema(schemaHistory.toList())
            return BytesAndSchemas(blob, schema, TransformsSchema.build(schema, serializerFactory))
        } finally {
            andFinally()
        }
    }

    internal fun andFinally() {
        objectHistory.clear()
        serializerHistory.clear()
        schemaHistory.clear()
    }


    internal fun <T : Any> _serialize(obj: T, context: SerializationContext): SerializedBytes<T> {
        val opt = _serialize(obj, context, true)

        var verify = System.getProperty("sb4b.corda.node.verifyOptimizations")?.toBoolean() ?: false
        if(verify) {
            andFinally()
            val unopt = _serialize(obj, context, false)
            if (opt.size != unopt.size) {
                throw java.lang.IllegalStateException()
            }
            for (x in 0 until opt.size) {
                if (opt.bytes[opt.offset + x] != unopt.bytes[unopt.offset + x]) {
                    throw java.lang.IllegalStateException()
                }
            }
        }

        return opt;
    }

    internal fun <T : Any> _serialize(obj: T, context: SerializationContext, optimize: Boolean): SerializedBytes<T> {
        val data = Data.Factory.create()
        data.withDescribed(Envelope.DESCRIPTOR_OBJECT) {
            withList {
                writeObject(obj, this, context)
                val schema = Schema(schemaHistory.toList())
                var transformedSchema = TransformsSchema.build(schema, serializerFactory)
                if(optimize && transformedSchema.types.isNotEmpty()){
                    throw IllegalStateException("schema transformations not yet supported when serialization optimizations are applied")
                }
                writeSchema(schema, this, context, optimize)
                writeTransformSchema(transformedSchema, this)
            }
        }

        return SerializedBytes(byteArrayOutput {
            var stream: OutputStream = it
            try {
                amqpMagic.writeTo(stream)
                val encoding = context.encoding
                if (encoding != null) {
                    SectionId.ENCODING.writeTo(stream)
                    (encoding as CordaSerializationEncoding).writeTo(stream)
                    stream = encoding.wrap(stream)
                }
                SectionId.DATA_AND_STOP.writeTo(stream)

                if(optimize){
                    val encode = data.encode()
                    val encodeBuffer = encode.asByteBuffer()

                    var contextImpl = context as SerializationContextImpl
                    val schemaBinary = contextImpl.serializeSchema(Schema(schemaHistory.toList()))

                    val emptyTransformLength = 13
                    val placeHolderLength = 10

                    // see ListElement in proton
                    var listTypeOffset = 10;
                    var listSize : Int

                    encodeBuffer.position(listTypeOffset)
                    var listType = encodeBuffer.get()
                    if(listType == 0x45.toByte()){
                        throw java.lang.IllegalStateException("empty list not implemented")
                    }else if(listType == 0xc0.toByte()) {
                        listSize = encodeBuffer.get().toInt().and(255) - 1
                        if(encodeBuffer.get() != 3.toByte()) throw java.lang.IllegalStateException("unknown envelop")
                    }else if(listType == 0xd0.toByte()){
                        listSize =  encodeBuffer.int - 4
                        if(encodeBuffer.int != 3) throw java.lang.IllegalStateException("unknown envelop")
                    }else {
                        throw java.lang.IllegalStateException("unknown list type")
                    }

                    // write header
                    stream.write(encode.array, encode.arrayOffset, listTypeOffset)

                    // write list size
                    var newListSize = listSize - placeHolderLength + schemaBinary.length
                    if(newListSize <= 254){
                        stream.write(0xc0)
                        stream.write(newListSize + 1)
                        stream.write(3)
                    }else{
                        // list type
                        stream.write(0xd0)

                        // write list length as int
                        stream.write(ByteBuffer.allocate(4).putInt(newListSize + 4).array())

                        // write count=3 for [data, schema, transform]
                        stream.write(0)
                        stream.write(0)
                        stream.write(0)
                        stream.write(3)
                    }

                    // write data
                    var placeHolderMagic = 13.toByte()
                    val dataLength = encode.length - emptyTransformLength - placeHolderLength - encodeBuffer.position()
                    if(encode.array[encode.arrayOffset + encode.length - emptyTransformLength - placeHolderLength] != placeHolderMagic)
                    stream.write(encode.array, encode.arrayOffset + encodeBuffer.position(), dataLength)

                    // write schema
                    stream.write(schemaBinary.array, schemaBinary.arrayOffset, schemaBinary.length)

                    // write transform
                    stream.write(encode.array, encode.arrayOffset + encode.length - emptyTransformLength, emptyTransformLength)
                }else {
                    stream.alsoAsByteBuffer(data.encodedSize().toInt(), data::encode)
                }
            } finally {
                stream.close()
            }
        })
    }

    internal fun writeObject(obj: Any, data: Data, context: SerializationContext) {
        writeObject(obj, data, obj.javaClass, context)
    }




    open fun writeSchema(schema: Schema, data: Data, context: SerializationContext, optimize: Boolean) {
        if(optimize){
            var placeholder = byteArrayOf(0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11)
            data.putBinary(placeholder)
        }else {
            data.putObject(schema)
        }
    }

    open fun writeTransformSchema(transformsSchema: TransformsSchema, data: Data) {
        data.putObject(transformsSchema)
    }

    internal fun writeObjectOrNull(obj: Any?, data: Data, type: Type, context: SerializationContext, debugIndent: Int) {
        if (obj == null) {
            data.putNull()
        } else {
            writeObject(obj, data, if (type == TypeIdentifier.UnknownType.getLocalType()) obj.javaClass else type, context, debugIndent)
        }
    }

    internal fun writeObject(obj: Any, data: Data, type: Type, context: SerializationContext, debugIndent: Int = 0) {
        val serializer = serializerFactory.get(obj.javaClass, type)
        if (serializer !in serializerHistory) {
            serializerHistory.add(serializer)
            serializer.writeClassInfo(this)
        }

        val retrievedRefCount = objectHistory[obj]
        if (retrievedRefCount == null) {
            serializer.writeObject(obj, data, type, this, context, debugIndent)
            // Important to do it after serialization such that dependent object will have preceding reference numbers
            // assigned to them first as they will be first read from the stream on receiving end.
            // Skip for primitive types as they are too small and overhead of referencing them will be much higher than their content
            if (suitableForObjectReference(obj.javaClass)) {
                objectHistory[obj] = objectHistory.size
            }
        } else {
            data.writeReferencedObject(ReferencedObject(retrievedRefCount))
        }
    }

    internal open fun writeTypeNotations(vararg typeNotation: TypeNotation): Boolean {
        return schemaHistory.addAll(typeNotation)
    }

    internal open fun requireSerializer(type: Type) {
        if (type != Object::class.java && type.typeName != "?") {
            val resolvedType = when(type) {
                is WildcardType ->
                    if (type.upperBounds.size == 1) type.upperBounds[0]
                    else throw NotSerializableException("Cannot obtain upper bound for type $type")
                else -> type
            }

            val serializer = serializerFactory.get(resolvedType)
            if (serializer !in serializerHistory) {
                serializerHistory.add(serializer)
                serializer.writeClassInfo(this)
            }
        }
    }
}

