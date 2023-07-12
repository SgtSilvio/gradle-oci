package io.github.sgtsilvio.gradle.oci.internal.reactor.netty

import io.github.sgtsilvio.gradle.oci.internal.Resource
import io.netty.buffer.UnpooledByteBufAllocator
import reactor.netty.resources.LoopResources

/**
 * @author Silvio Giebl
 */
object OciLoopResources : Resource<LoopResources>() {

    override fun create() =
        LoopResourcesWithCustomByteBufAllocator(LoopResources.create("oci"), UnpooledByteBufAllocator.DEFAULT)

    override fun LoopResources.destroy() = dispose()
}
