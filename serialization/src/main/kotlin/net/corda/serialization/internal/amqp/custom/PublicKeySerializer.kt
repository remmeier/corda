package net.corda.serialization.internal.amqp.custom

import net.corda.core.crypto.Crypto
import net.corda.core.serialization.SerializationContext
import net.corda.core.utilities.loggerFor
import net.corda.serialization.internal.amqp.AMQPTypeIdentifiers
import net.corda.serialization.internal.amqp.CustomSerializer
import net.corda.serialization.internal.amqp.DeserializationInput
import net.corda.serialization.internal.amqp.RestrictedType
import net.corda.serialization.internal.amqp.Schema
import net.corda.serialization.internal.amqp.SerializationOutput
import net.corda.serialization.internal.amqp.SerializationSchemas
import org.apache.qpid.proton.codec.Data
import java.lang.reflect.Type
import java.security.MessageDigest
import java.security.PublicKey
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * A serializer that writes out a public key in X.509 format.
 */
object PublicKeySerializer
    : CustomSerializer.Implements<PublicKey>(
        PublicKey::class.java
) {

    private val logger by lazy { loggerFor<PublicKeySerializer>() }

    override val schemaForDocumentation = Schema(listOf(RestrictedType(
            type.toString(),
            "",
            listOf(type.toString()),
            AMQPTypeIdentifiers.primitiveTypeName(ByteArray::class.java),
            descriptor,
            emptyList()
    )))

    private const val MAX_CACHE_SIZE = 1000

    // by bounding the LRU map to half the cache size, cache size will not be exceeded
    private const val MAX_MRU_MAP_SIZE = MAX_CACHE_SIZE.shr(1)

    // PublicKey instances are cached since they can be expensive to construct from a byte array (math operations)
    private var cache = ConcurrentHashMap<String, PublicKey>()

    // no access to apache commons LRU map implementation due to deterministic JVM,
    // we emulate it here with a second "most recently used" map"
    private var mruMap = ConcurrentHashMap<String, PublicKey>()

    override fun writeDescribedObject(obj: PublicKey, data: Data, type: Type, output: SerializationOutput,
                                      context: SerializationContext
    ) {
        // TODO: Instead of encoding to the default X509 format, we could have a custom per key type (space-efficient) serialiser.
        output.writeObject(obj.encoded, data, clazz, context)
    }

    override fun readObject(obj: Any, schemas: SerializationSchemas, input: DeserializationInput,
                            context: SerializationContext
    ): PublicKey {
        val bits = input.readObject(obj, schemas, ByteArray::class.java, context) as ByteArray

        val cacheId = hash(bits)
        var publicKey = cache[cacheId]
        if(publicKey != null && !Arrays.equals(publicKey.encoded, bits)){
            logger.error("highly unlikely cache mismatch for public key {}, ensure the public key is not under attack", publicKey)
            publicKey = null
        }
        if(publicKey == null){
            publicKey = Crypto.decodePublicKey(bits)
            cache[cacheId] = publicKey
        }

        mruMap[cacheId] = publicKey

        // when lru cache grows to large, we make use of it as cache and start
        // populating it again with the newly de-serialized public keys
        // (concurrency may lead to a cache miss, but negligible)
        if(mruMap.size > MAX_MRU_MAP_SIZE){
            cache = mruMap
            mruMap = ConcurrentHashMap();
        }

        return publicKey
    }

    private fun hash(bits: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return Base64.getEncoder().encodeToString(md.digest(bits))
    }
}