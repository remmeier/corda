package net.corda.serialization.internal.amqp.custom

import net.corda.core.crypto.Crypto
import net.corda.core.serialization.SerializationContext
import net.corda.serialization.internal.amqp.AMQPTypeIdentifiers
import net.corda.serialization.internal.amqp.CustomSerializer
import net.corda.serialization.internal.amqp.DeserializationInput
import net.corda.serialization.internal.amqp.RestrictedType
import net.corda.serialization.internal.amqp.Schema
import net.corda.serialization.internal.amqp.SerializationOutput
import net.corda.serialization.internal.amqp.SerializationSchemas
import org.apache.qpid.proton.codec.Data
import java.lang.reflect.Type
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
    private var cache = ConcurrentHashMap<UUID, PublicKey>()

    // no access to apache commons LRU map implementation due to deterministic JVM, we emulate it here with a second "most recently used" map"
    private var mruMap = ConcurrentHashMap<UUID, PublicKey>()

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

        val cacheId = UUID.nameUUIDFromBytes(bits)
        var publicKey = cache[cacheId]
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
}