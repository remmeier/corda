package net.corda.serialization.internal

import io.netty.buffer.ByteBuf
import net.corda.core.DeleteForDJVM
import net.corda.core.KeepForDJVM
import net.corda.core.crypto.SecureHash
import net.corda.core.internal.copyBytes
import net.corda.core.serialization.ClassWhitelist
import net.corda.core.serialization.EncodingWhitelist
import net.corda.core.serialization.ObjectWithCompatibleContext
import net.corda.core.serialization.SerializationContext
import net.corda.core.serialization.SerializationCustomSerializer
import net.corda.core.serialization.SerializationElements
import net.corda.core.serialization.SerializationEncoding
import net.corda.core.serialization.SerializationFactory
import net.corda.core.serialization.SerializationMagic
import net.corda.core.serialization.SerializedBytes
import net.corda.core.utilities.ByteSequence
import net.corda.serialization.internal.amqp.Schema
import net.corda.serialization.internal.amqp.amqpMagic
import org.apache.qpid.proton.amqp.Binary
import org.apache.qpid.proton.codec.Data
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.NotSerializableException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.ConcurrentHashMap

internal object NullEncodingWhitelist : EncodingWhitelist {
    override fun acceptEncoding(encoding: SerializationEncoding) = false
}

@KeepForDJVM
data class SerializationContextImpl @JvmOverloads constructor(override val preferredSerializationVersion: SerializationMagic,
                                                              override val deserializationClassLoader: ClassLoader,
                                                              override val whitelist: ClassWhitelist,
                                                              override val properties: Map<Any, Any>,
                                                              override val objectReferencesEnabled: Boolean,
                                                              override val useCase: SerializationContext.UseCase,
                                                              override val encoding: SerializationEncoding?,
                                                              override val encodingWhitelist: EncodingWhitelist = NullEncodingWhitelist,
                                                              override val lenientCarpenterEnabled: Boolean = false,
                                                              override val carpenterDisabled: Boolean = false,
                                                              override val preventDataLoss: Boolean = false,
                                                              override val customSerializers: Set<SerializationCustomSerializer<*, *>> = emptySet()) : SerializationContext {

    private val schemaCache = ConcurrentHashMap<String, Binary>()

    private fun schemaCacheId(schema: Schema): String {
        var md = MessageDigest.getInstance("SHA-256")
        for(type in schema.types){
            md.update(type.id)
        }
        return String(md.digest())
    }

    fun serializeSchema(schema: Schema): Binary {
        var cacheId = schemaCacheId(schema)
        var schemaBytes = schemaCache[cacheId]
        return if(schemaBytes == null) {
            val data = Data.Factory.create()
            data.putObject(schema)
            schemaBytes = data.encode()
            schemaCache[cacheId] = schemaBytes
            schemaBytes
        }else{
            schemaBytes
        }
    }

    /**
     * {@inheritDoc}
     */
    override fun withAttachmentsClassLoader(attachmentHashes: List<SecureHash>): SerializationContext {
        return this
    }

    override fun withProperty(property: Any, value: Any): SerializationContext {
        return copy(properties = properties + (property to value))
    }

    override fun withoutReferences(): SerializationContext {
        return copy(objectReferencesEnabled = false)
    }

    override fun withLenientCarpenter(): SerializationContext = copy(lenientCarpenterEnabled = true)

    override fun withoutCarpenter(): SerializationContext = copy(carpenterDisabled = true)

    override fun withPreventDataLoss(): SerializationContext = copy(preventDataLoss = true)

    override fun withClassLoader(classLoader: ClassLoader): SerializationContext {
        return copy(deserializationClassLoader = classLoader)
    }

    override fun withWhitelisted(clazz: Class<*>): SerializationContext {
        return copy(whitelist = object : ClassWhitelist {
            override fun hasListed(type: Class<*>): Boolean = whitelist.hasListed(type) || type.name == clazz.name
        })
    }

    override fun withCustomSerializers(serializers: Set<SerializationCustomSerializer<*, *>>): SerializationContextImpl {
        return copy(customSerializers = customSerializers.union(serializers))
    }

    override fun withPreferredSerializationVersion(magic: SerializationMagic) = copy(preferredSerializationVersion = magic)
    override fun withEncoding(encoding: SerializationEncoding?) = copy(encoding = encoding)
    override fun withEncodingWhitelist(encodingWhitelist: EncodingWhitelist) = copy(encodingWhitelist = encodingWhitelist)
}

@KeepForDJVM
open class SerializationFactoryImpl(
        // TODO: This is read-mostly. Probably a faster implementation to be found.
        private val schemes: MutableMap<Pair<CordaSerializationMagic, SerializationContext.UseCase>, SerializationScheme>
) : SerializationFactory() {
    @DeleteForDJVM
    constructor() : this(ConcurrentHashMap())

    companion object {
        val magicSize = amqpMagic.size
    }

    private val creator: List<StackTraceElement> = Exception().stackTrace.asList()

    private val registeredSchemes: MutableCollection<SerializationScheme> = Collections.synchronizedCollection(mutableListOf())

    private val logger = LoggerFactory.getLogger(javaClass)

    private fun schemeFor(byteSequence: ByteSequence, target: SerializationContext.UseCase): Pair<SerializationScheme, CordaSerializationMagic> {
        // truncate sequence to at most magicSize, and make sure it's a copy to avoid holding onto large ByteArrays
        val magic = CordaSerializationMagic(byteSequence.slice(end = magicSize).copyBytes())
        val lookupKey = magic to target
        // ConcurrentHashMap.get() is lock free, but computeIfAbsent is not, even if the key is in the map already.
        return (schemes[lookupKey] ?: schemes.computeIfAbsent(lookupKey) {
            registeredSchemes.filter { it.canDeserializeVersion(magic, target) }.forEach { return@computeIfAbsent it } // XXX: Not single?
            logger.warn("Cannot find serialization scheme for: [$lookupKey, " +
                    "${if (magic == amqpMagic) "AMQP" else "UNKNOWN MAGIC"}] registeredSchemes are: $registeredSchemes")
            throw UnsupportedOperationException("Serialization scheme $lookupKey not supported.")
        }) to magic
    }

    @Throws(NotSerializableException::class)
    override fun <T : Any> deserialize(byteSequence: ByteSequence, clazz: Class<T>, context: SerializationContext): T {
        return asCurrent { withCurrentContext(context) { schemeFor(byteSequence, context.useCase).first.deserialize(byteSequence, clazz, context) } }
    }

    override fun <T : Any> extractElements(byteSequence: ByteSequence): SerializationElements {

        /*
        var stream: InputStream = ByteArrayInputStream(byteSequence.bytes, byteSequence.offset, byteSequence.size)
        readHeader(stream)
        stream = readSectionHeaders(stream)
        stream.skip(2 + 8) // described element header
         */

        val buffer = ByteBuffer.wrap(byteSequence.bytes, byteSequence.offset, byteSequence.size)
        readHeader(buffer)
        readSectionHeaders(buffer)
        buffer.position(buffer.position() + 2 + 8) // skip described element header

        val listSize = readRootListSize(buffer)




        /*
        DateImpl/DataDecoder:

        static int decode(ByteBuffer b, Data data)
        {
            if(b.hasRemaining())
            {
                int position = b.position();
                TypeConstructor c = readConstructor(b);
                final int size = c.size(b);
                if(b.remaining() >= size)
                {
                    c.parse(b, data);
                    return 1+size;
                }
                else
                {
                    b.position(position);
                    return -4;
                }
            }
            return 0;
        }
         */





        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    private fun readRootListSize(buffer: ByteBuffer): Int {
        var listType = buffer.get()
        if(listType == 0x45.toByte()){
            throw java.lang.IllegalStateException("empty list not implemented")
        }else if(listType == 0xc0.toByte()) {
            val listSize = buffer.get().toInt().and(255) - 1
            if(buffer.get() != 3.toByte()) throw java.lang.IllegalStateException("unknown envelop")
            return listSize
        }else if(listType == 0xd0.toByte()){
            val listSize =  buffer.int - 4
            // expecting data, schema and transformation
            if(buffer.int != 3) throw java.lang.IllegalStateException("unknown envelop")
        }else {
            throw java.lang.IllegalStateException("unknown list type")
        }
    }

    private fun readSectionHeaders(buffer: ByteBuffer): ByteBuffer {
        var sectionId = SectionId.values()[buffer.get().toInt()]
        // var wrappedStream = buffer
        if(sectionId == SectionId.ENCODING){
            // var encoding = CordaSerializationEncoding.values()[buffer.get().toInt()]
            // sectionId = SectionId.values()[buffer.get().toInt()]
            // wrappedStream = encoding.wrap(buffer)
            throw java.lang.UnsupportedOperationException("encoding not supported yet")
        }
        if(sectionId != SectionId.DATA_AND_STOP) throw java.lang.UnsupportedOperationException("unknown sectionId")
        return buffer
    }

    private fun readHeader(buffer: ByteBuffer) {
        for(i in 0 until amqpMagic.size - 1 ){
            if(buffer.get() != amqpMagic.bytes[amqpMagic.offset + i]) throw UnsupportedOperationException("header not recognized")
        }
    }

    class AmqpSerializationElements(
            override val dataBits: ByteArray,
            override val schemaBits: ByteArray,
            override val schemaTransformationBits: ByteArray,
            override val schemaId: String) : SerializationElements{

    }

    @Throws(NotSerializableException::class)
    override fun <T : Any> deserializeWithCompatibleContext(byteSequence: ByteSequence, clazz: Class<T>, context: SerializationContext): ObjectWithCompatibleContext<T> {
        return asCurrent {
            withCurrentContext(context) {
                val (scheme, magic) = schemeFor(byteSequence, context.useCase)
                val deserializedObject = scheme.deserialize(byteSequence, clazz, context)
                ObjectWithCompatibleContext(deserializedObject, context.withPreferredSerializationVersion(magic))
            }
        }
    }

    override fun <T : Any> serialize(obj: T, context: SerializationContext): SerializedBytes<T> {
        return asCurrent { withCurrentContext(context) { schemeFor(context.preferredSerializationVersion, context.useCase).first.serialize(obj, context) } }
    }

    fun registerScheme(scheme: SerializationScheme) {
        check(schemes.isEmpty()) { "All serialization schemes must be registered before any scheme is used." }
        registeredSchemes += scheme
    }

    override fun toString(): String {
        return "${this.javaClass.name} registeredSchemes=$registeredSchemes ${creator.joinToString("\n")}"
    }

    override fun equals(other: Any?): Boolean {
        return other is SerializationFactoryImpl && other.registeredSchemes == this.registeredSchemes
    }

    override fun hashCode(): Int = registeredSchemes.hashCode()
}

@KeepForDJVM
interface SerializationScheme {
    fun canDeserializeVersion(magic: CordaSerializationMagic, target: SerializationContext.UseCase): Boolean
    @Throws(NotSerializableException::class)
    fun <T : Any> deserialize(byteSequence: ByteSequence, clazz: Class<T>, context: SerializationContext): T

    @Throws(NotSerializableException::class)
    fun <T : Any> serialize(obj: T, context: SerializationContext): SerializedBytes<T>
}
