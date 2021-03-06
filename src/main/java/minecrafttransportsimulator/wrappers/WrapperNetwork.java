package minecrafttransportsimulator.wrappers;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;

import io.netty.buffer.ByteBuf;
import minecrafttransportsimulator.MTS;
import minecrafttransportsimulator.packets.components.APacketBase;
import minecrafttransportsimulator.packets.instances.PacketPlayerChatMessage;
import minecrafttransportsimulator.packets.instances.PacketVehicleControlAnalog;
import minecrafttransportsimulator.packets.instances.PacketVehicleControlDigital;
import minecrafttransportsimulator.packets.instances.PacketVehicleInstruments;
import minecrafttransportsimulator.packets.instances.PacketVehicleLightToggle;
import minecrafttransportsimulator.packets.instances.PacketVehicleWrenchGUI;
import minecrafttransportsimulator.vehicles.main.EntityVehicleE_Powered;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

/**Wrapper for the MC networking system.  This wrapper allows us to send packets
 * around without actually creating packet classes.  Instead, we simply pass-in an
 * object to send over, which contains a handler for how to handle said object.
 *
 * @author don_bruce
 */
public class WrapperNetwork{
	private static final SimpleNetworkWrapper network = NetworkRegistry.INSTANCE.newSimpleChannel(MTS.MODID);
	private static final BiMap<Byte, Class<? extends APacketBase>> packetMappings = HashBiMap.create();
	
	/**
	 *  This method is responsible for registering all packets.
	 */
	public static void init(){
		//First register the main wrapper packet.
		network.registerMessage(WrapperHandler.class, WrapperPacket.class, 0, Side.CLIENT);
		network.registerMessage(WrapperHandler.class, WrapperPacket.class, 1, Side.SERVER);
		
		//Now register all classes in the minecrafttransportsimulator.packets.instances package.
		//Ideally this could be done via reflection, but it doesn't work too well so we don't do that.
		byte packetIndex = 0;
		packetMappings.put(packetIndex++, PacketPlayerChatMessage.class);
		packetMappings.put(packetIndex++, PacketVehicleControlAnalog.class);
		packetMappings.put(packetIndex++, PacketVehicleControlDigital.class);
		packetMappings.put(packetIndex++, PacketVehicleInstruments.class);
		packetMappings.put(packetIndex++, PacketVehicleLightToggle.class);
		packetMappings.put(packetIndex++, PacketVehicleWrenchGUI.class);
	}
	
	/**
	 *  Gets the index for the passed-in packet from the mapping.
	 */
	public static byte getPacketIndex(APacketBase packet){
		return packetMappings.inverse().get(packet.getClass());
	}
	
	/**
	 *  Sends the passed-in packet to the server.
	 */
	public static void sendToServer(APacketBase packet){
		network.sendToServer(new WrapperPacket(packet));
	}
	
	/**
	 *  Sends the passed-in packet to all clients.
	 */
	public static void sendToAllClients(APacketBase packet){
		network.sendToAll(new WrapperPacket(packet));
	}
	
	/**
	 *  Sends the passed-in packet to all clients tracking the
	 *  passed-in vehicle.  Useful for preventing packets going to
	 *  vehicles that don't actually exist on clients due to them
	 *  being far away.
	 */
	public static void sendToClientsTracking(APacketBase packet, EntityVehicleE_Powered trackingEntity){
		network.sendToAllTracking(new WrapperPacket(packet), trackingEntity);
	}
	
	/**
	 *  Sends the passed-in packet to the specified player.
	 *  Note that this may ONLY be called on the server, as
	 *  clients don't know about other player's network pipelines.
	 *  This is package-private as this gets fired from {@link WrapperPlayer},
	 *  since we need an actual player instance here rather than a wrapper, so we
	 *  shouldn't be able to call this from non-wrapper code.
	 */
	//TODO make this private when vehicle interaction gets wrapper interaction.
	public static void sendToPlayer(APacketBase packet, EntityPlayerMP player){
		network.sendTo(new WrapperPacket(packet), player);
	}
	
	
	/**
	 *  Custom class for packets.  Allows for a common packet to be used for all MC versions, 
	 *  as well as less boilerplate code due to thread operations.  Note that when this packet 
	 *  arrives on the other side of the pipeline, MC won't know what class to construct.
	 *  That's up to us to handle via the packet's first byte.
	 */
	public static class WrapperPacket implements IMessage{
		private APacketBase packet;
		
		/**Do NOT call!  Required to keep Forge from crashing.**/
		public WrapperPacket(){}
		
		public WrapperPacket(APacketBase packet){
			this.packet = packet;
		}
				
		@Override
		public void fromBytes(ByteBuf buf){
			byte packetIndex = buf.readByte();
			try{
				Class<? extends APacketBase> packetClass = packetMappings.get(packetIndex);
				packet = packetClass.getConstructor(ByteBuf.class).newInstance(buf);
			}catch(Exception e){
				e.printStackTrace();
			}
		}

		@Override
		public void toBytes(ByteBuf buf){
			packet.writeToBuffer(buf);
		}
	};
	
	/**
	 *  Custom class for handling packets.  This handler will have an instance of the packet
	 *  class passed-in with all fields populated by {@link WrapperPacket#fromBytes}.
	 */
	public static class WrapperHandler implements IMessageHandler<WrapperPacket, IMessage>{

		@Override
		public IMessage onMessage(WrapperPacket message, MessageContext ctx){
			//Need to put this in a runnable to not run it on the network thread and get a CME.
			FMLCommonHandler.instance().getWorldThread(ctx.netHandler).addScheduledTask(new Runnable(){
				@Override
				public void run(){
					if(ctx.side.isServer()){
						message.packet.handle(new WrapperWorld(ctx.getServerHandler().player.world), new WrapperPlayer(ctx.getServerHandler().player));
					}else{
						message.packet.handle(new WrapperWorld(Minecraft.getMinecraft().world), new WrapperPlayer(Minecraft.getMinecraft().player));
					}
				}
			});
			return null;
		}
	};

}
