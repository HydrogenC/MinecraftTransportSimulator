package minecrafttransportsimulator.vehicles.main;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import minecrafttransportsimulator.baseclasses.VehicleSound;
import minecrafttransportsimulator.baseclasses.VehicleSound.SoundTypes;
import minecrafttransportsimulator.dataclasses.DamageSources.DamageSourceCrash;
import minecrafttransportsimulator.dataclasses.MTSRegistry;
import minecrafttransportsimulator.items.packs.ItemInstrument;
import minecrafttransportsimulator.jsondefs.JSONVehicle;
import minecrafttransportsimulator.jsondefs.JSONVehicle.VehiclePart;
import minecrafttransportsimulator.radio.RadioContainer;
import minecrafttransportsimulator.systems.ConfigSystem;
import minecrafttransportsimulator.vehicles.parts.APart;
import minecrafttransportsimulator.vehicles.parts.APartEngine;
import minecrafttransportsimulator.vehicles.parts.APartGroundDevice;
import minecrafttransportsimulator.vehicles.parts.PartBarrel;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**This class adds engine components for vehicles, such as fuel, throttle,
 * and electricity.  Contains numerous methods for gauges, HUDs, and fuel systems.
 * This is added on-top of the D level to keep the crazy movement calculations
 * seperate from the vehicle power overhead bits.  This is the first level of
 * class that can be used for references in systems as it's the last common class for
 * vehicles.  All other sub-levels are simply functional building-blocks to keep this
 *  class from having 1000+ lines of code and to better segment things out.
 * 
 * @author don_bruce
 */
public abstract class EntityVehicleE_Powered extends EntityVehicleD_Moving implements RadioContainer{
	public boolean soundsNeedInit;
	public boolean hornOn;
	public boolean sirenOn;
	
	public byte throttle;
	public double fuel;
	public boolean reverseThrust;
	public short reversePercent;
	
	public double electricPower = 12;
	public double electricUsage;
	public double electricFlow;
	public String fluidName = "";
	public Vec3d velocityVec = Vec3d.ZERO;
	
	public final Map<Byte, ItemInstrument> instruments = new HashMap<Byte, ItemInstrument>();
	public final Map<Byte, APartEngine> engines = new HashMap<Byte, APartEngine>();
	public final List<APartGroundDevice> wheels = new ArrayList<APartGroundDevice>();
	public final List<APartGroundDevice> groundedWheels = new ArrayList<APartGroundDevice>();
	
	private final List<LightType> lightsOn = new ArrayList<LightType>();
	private final List<VehicleSound> sounds = new ArrayList<VehicleSound>();
	
	public EntityVehicleE_Powered(World world){
		super(world);
	}
	
	public EntityVehicleE_Powered(World world, float posX, float posY, float posZ, float playerRotation, JSONVehicle definition){
		super(world, posX, posY, posZ, playerRotation, definition);
	}
	
	@Override
	public void onEntityUpdate(){
		super.onEntityUpdate();
		if(definition != null){
			updateHeadingVec();
			if(fuel <= 0){
				fuel = 0;
				fluidName = "";
			}
			
			//Turn on the DRLs if we have an engine on.
			boolean anyEngineOn = false;
			for(APartEngine engine : engines.values()){
				if(engine.state.running){
					anyEngineOn = true;
					break;
				}
			}
			changeLightStatus(LightType.DAYTIMERUNNINGLIGHT, anyEngineOn);
			
			//Set electric usage based on light status.
			if(electricPower > 2){
				for(LightType light : lightsOn){
					if(light.hasBeam){
						electricUsage += 0.0005F;
					}
				}
			}
			electricPower = Math.max(0, Math.min(13, electricPower -= electricUsage));
			electricFlow = electricUsage;
			electricUsage = 0;
			
			//Adjust reverse thrust variables.
			if(reverseThrust && reversePercent < 20){
				++reversePercent;
			}else if(!reverseThrust && reversePercent > 0){
				--reversePercent;
			}
			
			//Populate grounded wheels.  Needs to be independent of non-wheeled ground devices.
			groundedWheels.clear();
			for(APartGroundDevice wheel : this.wheels){
				if(wheel.isOnGround()){
					groundedWheels.add(wheel);
				}
			}
		}
	}
	
	@Override
	public void destroyAtPosition(double x, double y, double z){
		super.destroyAtPosition(x, y, z);
		//Spawn instruments in the world.
		for(ItemInstrument instrument : this.instruments.values()){
			ItemStack stack = new ItemStack(instrument);
			world.spawnEntity(new EntityItem(world, posX, posY, posZ, stack));
		}
		
		//Now find the controller to see who to display as the killer in the death message.
		Entity controller = null;
		for(Entity passenger : this.getPassengers()){
			if(this.getSeatForRider(passenger).isController && controller != null){
				controller = passenger;
				break;
			}
		}
		
		//Now damage all passengers, including the controller.
		for(Entity passenger : this.getPassengers()){
			if(passenger.equals(controller)){
				passenger.attackEntityFrom(new DamageSourceCrash(null, this.definition.general.type), (float) (ConfigSystem.configObject.damage.crashDamageFactor.value*velocity*20));
			}else{
				passenger.attackEntityFrom(new DamageSourceCrash(controller, this.definition.general.type), (float) (ConfigSystem.configObject.damage.crashDamageFactor.value*velocity*20));
			}
		}
		
		//Oh, and add explosions.  Because those are always fun.
		//Note that this is done after spawning all parts here and in the super call,
		//so although all parts are DROPPED, not all parts may actually survive the explosion.
		if(ConfigSystem.configObject.damage.explosions.value){
			double fuelPresent = this.fuel;
			for(APart part : getVehicleParts()){
				if(part instanceof PartBarrel){
					PartBarrel barrel = (PartBarrel) part;
					if(barrel.getFluid() != null){
						for(Map<String, Double> fuelEntry : ConfigSystem.configObject.fuel.fuels.values()){
							if(fuelEntry.containsKey(barrel.getFluid().getFluid())){
								fuelPresent += barrel.getFluidAmount()*fuelEntry.get(barrel.getFluid().getFluid());
								break;
							}
						}
					}
				}
			}
			world.newExplosion(this, x, y, z, (float) (fuelPresent/10000F + 1F), true, true);
		}
	}
	
	@Override
	protected float getCurrentMass(){
		return (float) (super.getCurrentMass() + this.fuel/50);
	}
	
	@Override
	public void addPart(APart part, boolean ignoreCollision){
		super.addPart(part, ignoreCollision);
		if(part instanceof APartEngine){
			//Because parts is a list, the #1 engine will always come before the #2 engine.
			//We can use this to determine where in the list this engine needs to go.
			byte engineNumber = 0;
			for(VehiclePart packPart : definition.parts){
				for(String type : packPart.types){
					if(type.startsWith("engine")){
						if(part.offset.x == packPart.pos[0] && part.offset.y == packPart.pos[1] && part.offset.z == packPart.pos[2]){
							engines.put(engineNumber, (APartEngine) part);
							return;
						}
						++engineNumber;
					}
				}
			}
		}else if(part instanceof APartGroundDevice){
			if(((APartGroundDevice) part).canBeDrivenByEngine()){
				wheels.add((APartGroundDevice) part);
			}
		}
	}
	
	@Override
	public void removePart(APart part, boolean playBreakSound){
		super.removePart(part, playBreakSound);
		byte engineNumber = 0;
		for(VehiclePart packPart : definition.parts){
			for(String type : packPart.types){
				if(type.startsWith("engine")){
					if(part.offset.x == packPart.pos[0] && part.offset.y == packPart.pos[1] && part.offset.z == packPart.pos[2]){
						engines.remove(engineNumber);
						return;
					}
					++engineNumber;
				}
			}
		}
		if(wheels.contains(part)){
			wheels.remove(part);
		}
	}
	
	protected void performGroundOperations(){
		float brakingFactor = getBrakingForceFactor();
		if(brakingFactor > 0){
			double groundSpeed = Math.hypot(motionX, motionZ)*Math.signum(velocity);
			groundSpeed -= 20F*brakingFactor/currentMass*Math.signum(velocity);
			if(Math.abs(groundSpeed) > 0.1){
				reAdjustGroundSpeed(groundSpeed);
			}else{
				motionX = 0;
				motionZ = 0;
				motionYaw = 0;
			}
		}
		
		float skiddingFactor = getSkiddingFactor();
		if(skiddingFactor != 0){
			Vec3d groundVelocityVec = new Vec3d(motionX, 0, motionZ).normalize();
			Vec3d groundHeadingVec = new Vec3d(headingVec.x, 0, headingVec.z).normalize();
			float vectorDelta = (float) groundVelocityVec.distanceTo(groundHeadingVec);
			byte velocitySign = (byte) (vectorDelta < 1 ? 1 : -1);
			if(vectorDelta > 0.001){
				vectorDelta = Math.min(skiddingFactor, vectorDelta);
				float yawTemp = rotationYaw;
				rotationYaw += vectorDelta;
				updateHeadingVec();
				reAdjustGroundSpeed(Math.hypot(motionX, motionZ)*velocitySign);
				rotationYaw = yawTemp;
			}
		}
		
		motionYaw += getTurningFactor();
	}
	
	
	//-----START OF LIGHT CODE-----
	public void changeLightStatus(LightType light, boolean isOn){
		if(isOn){
			if(!lightsOn.contains(light)){
				lightsOn.add(light);
			}
		}else{
			if(lightsOn.contains(light)){
				lightsOn.remove(light);
			}
		}
	}
	
	public boolean isLightOn(LightType light){
		return lightsOn.contains(light);
	}
	
	//-----START OF SOUND CODE-----
	@SideOnly(Side.CLIENT)
	public final void initSounds(){
		if(definition.motorized.hornSound != null){
			addSound(SoundTypes.HORN, null);
		}
		if(definition.motorized.sirenSound != null){
			addSound(SoundTypes.SIREN, null);
		}
	}
	
	@SideOnly(Side.CLIENT)
	public final List<VehicleSound> getSounds(){
		return this.sounds;
	}
	
	@SideOnly(Side.CLIENT)
	public final void addSound(SoundTypes typeToAdd, APart optionalPart){
		VehicleSound newSound = new VehicleSound(this, optionalPart, typeToAdd);
		//If we already have a sound for this part, remove it before adding this new one.
		for(byte i=0; i<sounds.size(); ++i){
			if(sounds.get(i).getSoundUniqueName().equals(newSound.getSoundUniqueName())){
				sounds.remove(i);
				break;
			}
		}
		sounds.add(newSound);
	}
	
	//-----START OF RADIO CODE-----
	@Override
	public double getDistanceTo(double x, double y, double z){
		//Check to see if the listener is a passenger of this vehicle.
		//If so, we should return a distance of 0.
		for(Entity entity : getPassengers()){
			if(entity.posX == x && entity.posY == y && entity.posZ == z){
				return 0;
			}
		}
		return Math.sqrt(Math.pow(this.posX - x, 2) + Math.pow(this.posY - y, 2) + Math.pow(this.posZ - z, 2));
	}
	
	@Override
	public boolean isValid(){
		return !this.isDead;
	}
			
    @Override
	public void readFromNBT(NBTTagCompound tagCompound){
    	this.soundsNeedInit = world.isRemote && definition == null; 
    	super.readFromNBT(tagCompound);
		this.throttle=tagCompound.getByte("throttle");
		this.fuel=tagCompound.getDouble("fuel");
		this.electricPower=tagCompound.getDouble("electricPower");
		this.fluidName=tagCompound.getString("fluidName");
		
		lightsOn.clear();
		String lightsOnString = tagCompound.getString("lightsOn");
		while(!lightsOnString.isEmpty()){
			String lightName = lightsOnString.substring(0, lightsOnString.indexOf(','));
			for(LightType light : LightType.values()){
				if(light.name().equals(lightName)){
					lightsOn.add(light);
					break;
				}
			}
			lightsOnString = lightsOnString.substring(lightsOnString.indexOf(',') + 1);
		}
		for(byte i = 0; i<definition.motorized.instruments.size(); ++i){
			String instrumentPackID;
			String instrumentSystemName;
			//Check to see if we were an old or new vehicle.  If we are old, load using the old naming convention.
			if(tagCompound.hasKey("vehicleName")){
				String instrumentInSlot = tagCompound.getString("instrumentInSlot" + i);
				if(!instrumentInSlot.isEmpty()){
					instrumentPackID = instrumentInSlot.substring(0, instrumentInSlot.indexOf(':'));
					instrumentSystemName =  instrumentInSlot.substring(instrumentInSlot.indexOf(':') + 1);
				}else{
					continue;
				}
			}else{
				instrumentPackID = tagCompound.getString("instrument" + i + "_packID");
				instrumentSystemName = tagCompound.getString("instrument" + i + "_systemName");
			}
			if(!instrumentPackID.isEmpty()){
				ItemInstrument instrument = (ItemInstrument) MTSRegistry.packItemMap.get(instrumentPackID).get(instrumentSystemName);
				//Check to prevent loading of faulty instruments for the wrong vehicle due to updates or stupid people.
				if(instrument != null && instrument.definition.general.validVehicles.contains(this.definition.general.type)){
					instruments.put(i, instrument);
				}
			}
		}
	}
    
	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound tagCompound){
		super.writeToNBT(tagCompound);		
		tagCompound.setByte("throttle", this.throttle);
		tagCompound.setDouble("fuel", this.fuel);
		tagCompound.setDouble("electricPower", this.electricPower);
		tagCompound.setString("fluidName", this.fluidName);
		
		String lightsOnString = "";
		for(LightType light : this.lightsOn){
			lightsOnString += light.name() + ",";
		}
		tagCompound.setString("lightsOn", lightsOnString);
		
		String[] instrumentsInSlots = new String[definition.motorized.instruments.size()];
		for(byte i=0; i<instrumentsInSlots.length; ++i){
			if(instruments.containsKey(i)){
				tagCompound.setString("instrument" + i + "_packID", instruments.get(i).definition.packID);
				tagCompound.setString("instrument" + i + "_systemName", instruments.get(i).definition.systemName);
			}
		}
		return tagCompound;
	}
	
	public static enum LightType{
		NAVIGATIONLIGHT(false),
		STROBELIGHT(false),
		TAXILIGHT(true),
		LANDINGLIGHT(true),
		BRAKELIGHT(false),
		BACKUPLIGHT(false),
		LEFTTURNLIGHT(false),
		RIGHTTURNLIGHT(false),
		LEFTINDICATORLIGHT(false),
		RIGHTINDICATORLIGHT(false),
		RUNNINGLIGHT(false),
		HEADLIGHT(true),
		EMERGENCYLIGHT(false),
		DAYTIMERUNNINGLIGHT(false);
		
		public final boolean hasBeam;
		private LightType(boolean hasBeam){
			this.hasBeam = hasBeam;
		}
	}
}
