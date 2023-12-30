package io.github.sgtsilvio.gradle.oci.internal.reactor.netty

import io.netty.buffer.ByteBufAllocator
import io.netty.channel.Channel
import io.netty.channel.EventLoopGroup
import reactor.core.publisher.Mono
import reactor.netty.resources.LoopResources
import java.time.Duration

/**
 * @author Silvio Giebl
 */
internal class LoopResourcesWithCustomByteBufAllocator(
    private val delegate: LoopResources,
    private val byteBufAllocator: ByteBufAllocator,
) : LoopResources {

    override fun daemon(): Boolean = delegate.daemon()

    override fun isDisposed(): Boolean = delegate.isDisposed

    override fun dispose() = delegate.dispose()

    override fun disposeLater(): Mono<Void> = delegate.disposeLater()

    override fun disposeLater(quietPeriod: Duration, timeout: Duration): Mono<Void> =
        delegate.disposeLater(quietPeriod, timeout)

    override fun <CHANNEL : Channel> onChannel(channelType: Class<CHANNEL>, group: EventLoopGroup): CHANNEL {
        val channel = delegate.onChannel(channelType, group)
        channel.config().allocator = byteBufAllocator
        return channel
    }

    override fun <CHANNEL : Channel> onChannelClass(
        channelType: Class<CHANNEL>,
        group: EventLoopGroup,
    ): Class<out CHANNEL> = delegate.onChannelClass(channelType, group)

    override fun onClient(useNative: Boolean): EventLoopGroup = delegate.onClient(useNative)

    override fun onServer(useNative: Boolean): EventLoopGroup = delegate.onServer(useNative)

    override fun onServerSelect(useNative: Boolean): EventLoopGroup = delegate.onServerSelect(useNative)
}
