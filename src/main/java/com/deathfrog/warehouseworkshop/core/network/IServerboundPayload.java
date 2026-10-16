package com.deathfrog.warehouseworkshop.core.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * Small helper for client-to-server addon payloads.
 */
public interface IServerboundPayload extends CustomPacketPayload
{
    default void sendToServer()
    {
        ClientPacketDistributor.sendToServer(this);
    }
}
