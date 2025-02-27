package extracells.part;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.util.StatCollector;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.*;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.config.SecurityPermissions;
import appeng.api.implementations.ICraftingPatternItem;
import appeng.api.implementations.tiles.ITileStorageMonitorable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingProviderHelper;
import appeng.api.networking.events.MENetworkCraftingPatternChange;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.security.MachineSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartCollisionHelper;
import appeng.api.parts.IPartRenderHelper;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.IStorageMonitorable;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.util.InventoryAdaptor;
import cpw.mods.fml.common.FMLLog;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import extracells.Extracells;
import extracells.api.IFluidInterface;
import extracells.api.crafting.IFluidCraftingPatternDetails;
import extracells.container.ContainerFluidInterface;
import extracells.container.IContainerListener;
import extracells.crafting.CraftingPattern;
import extracells.crafting.CraftingPattern2;
import extracells.gui.GuiFluidInterface;
import extracells.network.packet.other.IFluidSlotPartOrBlock;
import extracells.registries.ItemEnum;
import extracells.render.TextureManager;
import extracells.util.EmptyMeItemMonitor;
import extracells.util.FluidUtil;
import extracells.util.PermissionUtil;
import io.netty.buffer.ByteBuf;

public class PartFluidInterface extends PartECBase implements IFluidHandler, IFluidInterface, IFluidSlotPartOrBlock,
        ITileStorageMonitorable, IStorageMonitorable, IGridTickable, ICraftingProvider {

    private final HashMap<ICraftingPatternDetails, IFluidCraftingPatternDetails> patternConvert = new HashMap<ICraftingPatternDetails, IFluidCraftingPatternDetails>();

    List<IContainerListener> listeners = new ArrayList<IContainerListener>();
    private List<ICraftingPatternDetails> patternHandlers = new ArrayList<ICraftingPatternDetails>();
    private final List<IAEItemStack> requestedItems = new ArrayList<IAEItemStack>();
    private final List<IAEItemStack> removeList = new ArrayList<IAEItemStack>();
    private final List<IAEStack> export = new ArrayList<IAEStack>();
    private final ICraftingPatternDetails[] originalPatternsCache = new ICraftingPatternDetails[9];
    public final FluidInterfaceInventory inventory = new FluidInterfaceInventory();

    private boolean update = false;
    private final List<IAEStack> removeFromExport = new ArrayList<IAEStack>();
    private final List<IAEStack> addToExport = new ArrayList<IAEStack>();
    private final FluidTank tank = new FluidTank(10000);
    private IAEItemStack toExport = null;

    private final Item encodedPattern = AEApi.instance().definitions().items().encodedPattern().maybeItem().orNull();
    private final int tickCount = 0;
    private int fluidFilter = -1;
    public boolean doNextUpdate = false;
    private boolean needBreak = false;

    @Override
    public TickRateModulation tickingRequest(IGridNode node, int TicksSinceLastCall) {
        if (this.doNextUpdate) forceUpdate();
        IGrid grid = node.getGrid();
        if (grid == null) return TickRateModulation.IDLE;
        IStorageGrid storage = grid.getCache(IStorageGrid.class);
        if (storage == null) return TickRateModulation.IDLE;
        pushItems();
        boolean didWork = false;
        if (this.toExport != null) {
            storage.getItemInventory().injectItems(this.toExport, Actionable.MODULATE, new MachineSource(this));
            this.toExport = null;
            didWork = true;
        }
        if (this.update) {
            this.update = false;
            if (getGridNode() != null && getGridNode().getGrid() != null) {
                getGridNode().getGrid().postEvent(new MENetworkCraftingPatternChange(this, getGridNode()));
                getHost().markForSave();
            }
        }
        if (this.tank.getFluid() != null
                && FluidRegistry.getFluid(this.fluidFilter) != this.tank.getFluid().getFluid()) {
            FluidStack s = this.tank.drain(Extracells.basePartSpeed() * TicksSinceLastCall, false);
            if (s != null) {
                IAEFluidStack notAdded = storage.getFluidInventory().injectItems(
                        AEApi.instance().storage().createFluidStack(s),
                        Actionable.SIMULATE,
                        new MachineSource(this));
                int toAdd = s.amount - (notAdded != null ? (int) notAdded.getStackSize() : 0);
                IAEFluidStack actuallyNotInjected = storage.getFluidInventory().injectItems(
                        AEApi.instance().storage().createFluidStack(this.tank.drain(toAdd, true)),
                        Actionable.MODULATE,
                        new MachineSource(this));
                if (actuallyNotInjected != null) {
                    int returned = this.tank.fill(actuallyNotInjected.getFluidStack(), true);
                    if (returned != actuallyNotInjected.getStackSize()) {
                        FMLLog.severe(
                                "[ExtraCells2] Interface tank import at %d:%d,%d,%d voided %d mL of %s",
                                tile.getWorldObj().provider.dimensionId,
                                tile.xCoord,
                                tile.yCoord,
                                tile.zCoord,
                                actuallyNotInjected.getStackSize() - returned,
                                actuallyNotInjected.getFluid().getName());
                    }
                }
                this.doNextUpdate = true;
                this.needBreak = false;
                didWork = true;
            }
        }
        if ((this.tank.getFluid() == null
                || (this.tank.getFluid().getFluid() == FluidRegistry.getFluid(this.fluidFilter)
                        && this.tank.getFluidAmount() < this.tank.getCapacity()))
                && FluidRegistry.getFluid(this.fluidFilter) != null) {
            IAEFluidStack request = FluidUtil
                    .createAEFluidStack(this.fluidFilter, (long) Extracells.basePartSpeed() * TicksSinceLastCall);
            IAEFluidStack extracted = storage.getFluidInventory()
                    .extractItems(request, Actionable.SIMULATE, new MachineSource(this));
            if (extracted == null) return TickRateModulation.SLOWER;
            int accepted = this.tank.fill(extracted.getFluidStack(), false);
            if (accepted == 0) return TickRateModulation.SLOWER;
            request.setStackSize(Long.min(accepted, extracted.getStackSize()));
            extracted = storage.getFluidInventory().extractItems(request, Actionable.MODULATE, new MachineSource(this));
            if (extracted == null || extracted.getStackSize() <= 0) {
                return TickRateModulation.SLOWER;
            }
            accepted = this.tank.fill(extracted.getFluidStack(), true);
            if (extracted.getStackSize() != accepted) {
                // This should never happen, but log it in case it does
                FMLLog.severe(
                        "[ExtraCells2] Interface tank export at %d:%d,%d,%d voided %d mL of %s",
                        tile.getWorldObj().provider.dimensionId,
                        tile.xCoord,
                        tile.yCoord,
                        tile.zCoord,
                        extracted.getStackSize() - accepted,
                        request.getFluid().getName());
            }
            this.doNextUpdate = true;
            this.needBreak = false;
            didWork = true;
        }
        return didWork ? TickRateModulation.URGENT : TickRateModulation.SLOWER;
    }

    @Override
    public int cableConnectionRenderTo() {
        return 3;
    }

    @Override
    public boolean canDrain(ForgeDirection from, Fluid fluid) {
        FluidStack tankFluid = this.tank.getFluid();
        return tankFluid != null && tankFluid.getFluid() == fluid;
    }

    @Override
    public boolean canFill(ForgeDirection from, Fluid fluid) {
        return this.tank.fill(new FluidStack(fluid, 1), false) > 0;
    }

    @Override
    public FluidStack drain(ForgeDirection from, FluidStack resource, boolean doDrain) {
        FluidStack tankFluid = this.tank.getFluid();
        if (resource == null || tankFluid == null || tankFluid.getFluid() != resource.getFluid()) return null;
        return drain(from, resource.amount, doDrain);
    }

    @Override
    public FluidStack drain(ForgeDirection from, int maxDrain, boolean doDrain) {
        FluidStack drained = this.tank.drain(maxDrain, doDrain);
        if (drained != null) getHost().markForUpdate();
        this.doNextUpdate = true;
        return drained;
    }

    @Override
    public int fill(ForgeDirection from, FluidStack resource, boolean doFill) {
        if (resource == null) return 0;

        if ((this.tank.getFluid() == null || this.tank.getFluid().getFluid() == resource.getFluid())
                && resource.getFluid() == FluidRegistry.getFluid(this.fluidFilter)) {
            int added = this.tank.fill(resource.copy(), doFill);
            if (added == resource.amount) {
                this.doNextUpdate = true;
                return added;
            }
            added += fillToNetwork(new FluidStack(resource.getFluid(), resource.amount - added), doFill);
            this.doNextUpdate = true;
            return added;
        }

        int filled = 0;
        filled += fillToNetwork(resource, doFill);

        if (filled < resource.amount)
            filled += this.tank.fill(new FluidStack(resource.getFluid(), resource.amount - filled), doFill);
        if (filled > 0) getHost().markForUpdate();
        this.doNextUpdate = true;
        return filled;
    }

    public int fillToNetwork(FluidStack resource, boolean doFill) {
        IGridNode node = getGridNode(ForgeDirection.UNKNOWN);
        if (node == null || resource == null) return 0;
        IGrid grid = node.getGrid();
        if (grid == null) return 0;
        IStorageGrid storage = grid.getCache(IStorageGrid.class);
        if (storage == null) return 0;
        IAEFluidStack notRemoved = storage.getFluidInventory().injectItems(
                AEApi.instance().storage().createFluidStack(resource),
                doFill ? Actionable.MODULATE : Actionable.SIMULATE,
                new MachineSource(this));
        if (notRemoved == null) return resource.amount;
        return (int) (resource.amount - notRemoved.getStackSize());
    }

    private void forceUpdate() {
        getHost().markForUpdate();
        for (IContainerListener listener : this.listeners) {
            if (listener != null) listener.updateContainer();
        }
        this.doNextUpdate = false;
    }

    @Override
    public void getBoxes(IPartCollisionHelper bch) {
        bch.addBox(2, 2, 14, 14, 14, 16);
        bch.addBox(5, 5, 12, 11, 11, 14);
    }

    @Override
    public Object getClientGuiElement(EntityPlayer player) {
        return new GuiFluidInterface(player, this, getSide());
    }

    @SideOnly(Side.CLIENT)
    private World getClientWorld() {
        return Minecraft.getMinecraft().theWorld;
    }

    @Override
    public void getDrops(List<ItemStack> drops, boolean wrenched) {
        for (int i = 0; i < this.inventory.getSizeInventory(); i++) {
            ItemStack pattern = this.inventory.getStackInSlot(i);
            if (pattern != null) drops.add(pattern);
        }
    }

    @Override
    public Fluid getFilter(ForgeDirection side) {
        return FluidRegistry.getFluid(this.fluidFilter);
    }

    @Override
    public IMEMonitor<IAEFluidStack> getFluidInventory() {
        if (getGridNode(ForgeDirection.UNKNOWN) == null) return null;
        IGrid grid = getGridNode(ForgeDirection.UNKNOWN).getGrid();
        if (grid == null) return null;
        IStorageGrid storage = grid.getCache(IStorageGrid.class);
        if (storage == null) return null;
        return storage.getFluidInventory();
    }

    @Override
    public IFluidTank getFluidTank(ForgeDirection side) {
        return this.tank;
    }

    @Override
    public IMEMonitor<IAEItemStack> getItemInventory() {
        return new EmptyMeItemMonitor();
    }

    @Override
    public IStorageMonitorable getMonitorable(ForgeDirection side, BaseActionSource src) {
        return this;
    }

    @Override
    public IInventory getPatternInventory() {
        return this.inventory;
    }

    @Override
    public double getPowerUsage() {
        return 1.0D;
    }

    @Override
    public Object getServerGuiElement(EntityPlayer player) {
        return new ContainerFluidInterface(player, this);
    }

    @Override
    public FluidTankInfo[] getTankInfo(ForgeDirection from) {
        return new FluidTankInfo[] { this.tank.getInfo() };
    }

    @Override
    public TickingRequest getTickingRequest(IGridNode node) {
        return new TickingRequest(5, 120, false, false);
    }

    @Override
    public List<String> getWailaBodey(NBTTagCompound tag, List<String> list) {
        FluidStack fluid = null;
        int id = -1;
        int amount = 0;
        if (tag.hasKey("fluidID") && tag.hasKey("amount")) {
            id = tag.getInteger("fluidID");
            amount = tag.getInteger("amount");
        }
        if (id != -1) fluid = new FluidStack(id, amount);
        if (fluid == null) {
            list.add(
                    StatCollector.translateToLocal("extracells.tooltip.fluid") + ": "
                            + StatCollector.translateToLocal("extracells.tooltip.empty1"));
            list.add(StatCollector.translateToLocal("extracells.tooltip.amount") + ": 0mB / 10000mB");
        } else {
            list.add(StatCollector.translateToLocal("extracells.tooltip.fluid") + ": " + fluid.getLocalizedName());
            list.add(
                    StatCollector.translateToLocal("extracells.tooltip.amount") + ": " + fluid.amount + "mB / 10000mB");
        }
        return list;
    }

    @Override
    public NBTTagCompound getWailaTag(NBTTagCompound tag) {
        if (this.tank.getFluid() == null || this.tank.getFluid().getFluid() == null) tag.setInteger("fluidID", -1);
        else tag.setInteger("fluidID", this.tank.getFluid().getFluidID());
        tag.setInteger("amount", this.tank.getFluidAmount());
        return tag;
    }

    @Override
    public void initializePart(ItemStack partStack) {
        if (partStack.hasTagCompound()) {
            readFilter(partStack.getTagCompound());
        }
    }

    @Override
    public boolean isBusy() {
        return !this.export.isEmpty();
    }

    private ItemStack makeCraftingPatternItem(ICraftingPatternDetails details) {
        if (details == null) return null;
        NBTTagList in = new NBTTagList();
        NBTTagList out = new NBTTagList();
        for (IAEItemStack s : details.getInputs()) {
            if (s == null) in.appendTag(new NBTTagCompound());
            else in.appendTag(s.getItemStack().writeToNBT(new NBTTagCompound()));
        }
        for (IAEItemStack s : details.getOutputs()) {
            if (s == null) out.appendTag(new NBTTagCompound());
            else out.appendTag(s.getItemStack().writeToNBT(new NBTTagCompound()));
        }
        NBTTagCompound itemTag = new NBTTagCompound();
        itemTag.setTag("in", in);
        itemTag.setTag("out", out);
        itemTag.setBoolean("crafting", details.isCraftable());
        ItemStack pattern = new ItemStack(this.encodedPattern);
        pattern.setTagCompound(itemTag);
        return pattern;
    }

    @Override
    public boolean onActivate(EntityPlayer player, Vec3 pos) {
        if (PermissionUtil.hasPermission(player, SecurityPermissions.BUILD, (IPart) this)) {
            return super.onActivate(player, pos);
        }
        return false;
    }

    @Override
    public void provideCrafting(ICraftingProviderHelper craftingTracker) {
        this.patternHandlers = new ArrayList<ICraftingPatternDetails>();
        this.patternConvert.clear();

        if (!this.isActive()) {
            return;
        }
        ItemStack[] inv = this.inventory.inv;
        for (int i = 0, invLength = inv.length; i < invLength; i++) {
            ItemStack currentPatternStack = inv[i];
            if (currentPatternStack != null && currentPatternStack.getItem() != null
                    && currentPatternStack.getItem() instanceof ICraftingPatternItem) {
                ICraftingPatternItem currentPattern = (ICraftingPatternItem) currentPatternStack.getItem();

                ICraftingPatternDetails originalPattern = originalPatternsCache[i];
                if (originalPattern == null) {
                    originalPattern = currentPattern.getPatternForItem(currentPatternStack, getGridNode().getWorld());
                    originalPatternsCache[i] = originalPattern;
                }
                if (originalPattern != null) {
                    IFluidCraftingPatternDetails pattern = new CraftingPattern2(originalPattern);
                    this.patternHandlers.add(pattern);
                    ItemStack is = makeCraftingPatternItem(pattern);
                    if (is == null) continue;
                    ICraftingPatternDetails p = ((ICraftingPatternItem) is.getItem())
                            .getPatternForItem(is, getGridNode().getWorld());
                    if (p == null) continue;
                    this.patternConvert.put(p, pattern);
                    craftingTracker.addCraftingOption(this, p);
                }
            }
        }
    }

    private void pushItems() {
        for (IAEStack s : this.removeFromExport) {
            this.export.remove(s);
        }
        this.removeFromExport.clear();
        for (IAEStack s : this.addToExport) {
            this.export.add(s);
        }
        this.addToExport.clear();
        if (getGridNode().getWorld() == null || this.export.isEmpty()) return;
        ForgeDirection dir = getSide();
        TileEntity tile = getGridNode().getWorld().getTileEntity(
                getGridNode().getGridBlock().getLocation().x + dir.offsetX,
                getGridNode().getGridBlock().getLocation().y + dir.offsetY,
                getGridNode().getGridBlock().getLocation().z + dir.offsetZ);
        if (tile != null) {
            IAEStack stack0 = this.export.get(0);
            IAEStack stack = stack0.copy();
            if (stack instanceof IAEItemStack && tile instanceof IInventory) {
                InventoryAdaptor adaptor = InventoryAdaptor.getAdaptor(tile, dir.getOpposite());
                if (adaptor != null) {
                    final ItemStack adding = ((IAEItemStack) stack).getItemStack();
                    final int originalAddingAmount = adding.stackSize;
                    final ItemStack leftover = adaptor.addItems(adding);
                    // calculate how much to remove, because getItemStack() limits stack size from a long to an int
                    final int leftoverAmount = leftover == null ? 0 : leftover.stackSize;
                    final int removedAmount = originalAddingAmount - leftoverAmount;
                    if ((long) removedAmount == stack0.getStackSize()) {
                        this.export.remove(0);
                    } else {
                        this.export.get(0).setStackSize(stack0.getStackSize() - removedAmount);
                    }
                }
            } else if (stack instanceof IAEFluidStack && tile instanceof IFluidHandler) {
                IFluidHandler handler = (IFluidHandler) tile;
                IAEFluidStack fluid = (IAEFluidStack) stack;
                if (handler.canFill(dir.getOpposite(), fluid.copy().getFluid())) {
                    int amount = handler.fill(dir.getOpposite(), fluid.getFluidStack().copy(), false);
                    if (amount == 0) return;
                    if (amount == fluid.getStackSize()) {
                        amount = handler.fill(dir.getOpposite(), fluid.getFluidStack().copy(), true);
                    }
                    if (amount == fluid.getStackSize()) {
                        this.removeFromExport.add(stack0);
                    } else {
                        IAEFluidStack f = fluid.copy();
                        f.setStackSize(f.getStackSize() - amount);
                        FluidStack fl = fluid.getFluidStack().copy();
                        fl.amount = amount;
                        handler.fill(dir.getOpposite(), fl, true);
                        this.removeFromExport.add(stack0);
                        this.addToExport.add(f);
                    }
                }
            }
        }
    }

    @Override
    public boolean pushPattern(ICraftingPatternDetails patDetails, InventoryCrafting table) {
        if (isBusy() || !this.patternConvert.containsKey(patDetails)) return false;
        ICraftingPatternDetails patternDetails = this.patternConvert.get(patDetails);
        if (patternDetails instanceof CraftingPattern) {
            CraftingPattern patter = (CraftingPattern) patternDetails;
            HashMap<Fluid, Long> fluids = new HashMap<Fluid, Long>();
            for (IAEFluidStack stack : patter.getCondensedFluidInputs()) {
                if (fluids.containsKey(stack.getFluid())) {
                    Long amount = fluids.get(stack.getFluid()) + stack.getStackSize();
                    fluids.remove(stack.getFluid());
                    fluids.put(stack.getFluid(), amount);
                } else {
                    fluids.put(stack.getFluid(), stack.getStackSize());
                }
            }
            IGrid grid = getGridNode().getGrid();
            if (grid == null) return false;
            IStorageGrid storage = grid.getCache(IStorageGrid.class);
            if (storage == null) return false;
            for (Fluid fluid : fluids.keySet()) {
                Long amount = fluids.get(fluid);
                IAEFluidStack extractFluid = storage.getFluidInventory().extractItems(
                        AEApi.instance().storage().createFluidStack(new FluidStack(fluid, amount.intValue())),
                        Actionable.SIMULATE,
                        new MachineSource(this));
                if (extractFluid == null || extractFluid.getStackSize() != amount) {
                    return false;
                }
            }
            for (Fluid fluid : fluids.keySet()) {
                Long amount = fluids.get(fluid);
                IAEFluidStack extractFluid = storage.getFluidInventory().extractItems(
                        AEApi.instance().storage().createFluidStack(new FluidStack(fluid, amount.intValue())),
                        Actionable.MODULATE,
                        new MachineSource(this));
                this.export.add(extractFluid);
            }
            for (IAEItemStack s : patter.getCondensedInputs()) {
                if (s == null) continue;
                if (s.getItem() == ItemEnum.FLUIDPATTERN.getItem()) {
                    this.toExport = s.copy();
                    continue;
                }
                this.export.add(s);
            }
        }
        return true;
    }

    public void readFilter(NBTTagCompound tag) {
        if (tag.hasKey("filter")) this.fluidFilter = tag.getInteger("filter");
    }

    @Override
    public void readFromNBT(NBTTagCompound data) {
        super.readFromNBT(data);
        if (data.hasKey("tank")) this.tank.readFromNBT(data.getCompoundTag("tank"));
        if (data.hasKey("filter")) this.fluidFilter = data.getInteger("filter");
        if (data.hasKey("inventory")) this.inventory.readFromNBT(data.getCompoundTag("inventory"));
        if (data.hasKey("export")) readOutputFromNBT(data.getCompoundTag("export"));
    }

    @Override
    public boolean readFromStream(ByteBuf data) throws IOException {
        super.readFromStream(data);
        NBTTagCompound tag = ByteBufUtils.readTag(data);
        if (tag.hasKey("tank")) this.tank.readFromNBT(tag.getCompoundTag("tank"));
        if (tag.hasKey("filter")) this.fluidFilter = tag.getInteger("filter");
        if (tag.hasKey("inventory")) this.inventory.readFromNBT(tag.getCompoundTag("inventory"));
        return true;
    }

    private void readOutputFromNBT(NBTTagCompound tag) {
        this.addToExport.clear();
        this.removeFromExport.clear();
        this.export.clear();
        int i = tag.getInteger("remove");
        for (int j = 0; j < i; j++) {
            if (tag.getBoolean("remove-" + j + "-isItem")) {
                IAEItemStack s = AEApi.instance().storage()
                        .createItemStack(ItemStack.loadItemStackFromNBT(tag.getCompoundTag("remove-" + j)));
                s.setStackSize(tag.getLong("remove-" + j + "-amount"));
                this.removeFromExport.add(s);
            } else {
                IAEFluidStack s = AEApi.instance().storage()
                        .createFluidStack(FluidStack.loadFluidStackFromNBT(tag.getCompoundTag("remove-" + j)));
                s.setStackSize(tag.getLong("remove-" + j + "-amount"));
                this.removeFromExport.add(s);
            }
        }
        i = tag.getInteger("add");
        for (int j = 0; j < i; j++) {
            if (tag.getBoolean("add-" + j + "-isItem")) {
                IAEItemStack s = AEApi.instance().storage()
                        .createItemStack(ItemStack.loadItemStackFromNBT(tag.getCompoundTag("add-" + j)));
                s.setStackSize(tag.getLong("add-" + j + "-amount"));
                this.addToExport.add(s);
            } else {
                IAEFluidStack s = AEApi.instance().storage()
                        .createFluidStack(FluidStack.loadFluidStackFromNBT(tag.getCompoundTag("add-" + j)));
                s.setStackSize(tag.getLong("add-" + j + "-amount"));
                this.addToExport.add(s);
            }
        }
        i = tag.getInteger("export");
        for (int j = 0; j < i; j++) {
            if (tag.getBoolean("export-" + j + "-isItem")) {
                IAEItemStack s = AEApi.instance().storage()
                        .createItemStack(ItemStack.loadItemStackFromNBT(tag.getCompoundTag("export-" + j)));
                s.setStackSize(tag.getLong("export-" + j + "-amount"));
                this.export.add(s);
            } else {
                IAEFluidStack s = AEApi.instance().storage()
                        .createFluidStack(FluidStack.loadFluidStackFromNBT(tag.getCompoundTag("export-" + j)));
                s.setStackSize(tag.getLong("export-" + j + "-amount"));
                this.export.add(s);
            }
        }
    }

    public void registerListener(IContainerListener listener) {
        this.listeners.add(listener);
    }

    public void removeListener(IContainerListener listener) {
        this.listeners.remove(listener);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void renderInventory(IPartRenderHelper rh, RenderBlocks renderer) {
        Tessellator ts = Tessellator.instance;

        IIcon side = TextureManager.BUS_SIDE.getTexture();
        rh.setTexture(side, side, side, TextureManager.INTERFACE.getTextures()[0], side, side);
        rh.setBounds(2, 2, 14, 14, 14, 16);
        rh.renderInventoryBox(renderer);

        rh.renderInventoryFace(TextureManager.INTERFACE.getTextures()[0], ForgeDirection.SOUTH, renderer);

        rh.setTexture(side);
        rh.setBounds(5, 5, 12, 11, 11, 14);
        rh.renderInventoryBox(renderer);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void renderStatic(int x, int y, int z, IPartRenderHelper rh, RenderBlocks renderer) {
        Tessellator ts = Tessellator.instance;

        IIcon side = TextureManager.BUS_SIDE.getTexture();
        rh.setTexture(side, side, side, TextureManager.INTERFACE.getTextures()[0], side, side);
        rh.setBounds(2, 2, 14, 14, 14, 16);
        rh.renderBlock(x, y, z, renderer);

        ts.setBrightness(20971520);
        rh.renderFace(x, y, z, TextureManager.INTERFACE.getTextures()[0], ForgeDirection.SOUTH, renderer);

        rh.setTexture(side);
        rh.setBounds(5, 5, 12, 11, 11, 14);
        rh.renderBlock(x, y, z, renderer);
    }

    @Override
    public void setFilter(ForgeDirection side, Fluid fluid) {
        if (fluid == null) {
            this.fluidFilter = -1;
            this.doNextUpdate = true;
            return;
        }
        this.fluidFilter = fluid.getID();
        this.doNextUpdate = true;
    }

    @Override
    public void setFluid(int _index, Fluid _fluid, EntityPlayer _player) {
        setFilter(ForgeDirection.getOrientation(_index), _fluid);
    }

    @Override
    public void setFluidTank(ForgeDirection side, FluidStack fluid) {
        this.tank.setFluid(fluid);
        this.doNextUpdate = true;
    }

    private class FluidInterfaceInventory implements IInventory {

        private final ItemStack[] inv = new ItemStack[9];

        @Override
        public void closeInventory() {}

        @Override
        public ItemStack decrStackSize(int slot, int amt) {
            ItemStack stack = getStackInSlot(slot);
            if (stack != null) {
                if (stack.stackSize <= amt) {
                    setInventorySlotContents(slot, null);
                } else {
                    stack = stack.splitStack(amt);
                    if (stack.stackSize == 0) {
                        setInventorySlotContents(slot, null);
                    }
                }
            }
            PartFluidInterface.this.update = true;
            return stack;
        }

        @Override
        public String getInventoryName() {
            return "inventory.fluidInterface";
        }

        @Override
        public int getInventoryStackLimit() {
            return 1;
        }

        @Override
        public int getSizeInventory() {
            return this.inv.length;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return this.inv[slot];
        }

        @Override
        public ItemStack getStackInSlotOnClosing(int slot) {
            return null;
        }

        @Override
        public boolean hasCustomInventoryName() {
            return false;
        }

        @Override
        public boolean isItemValidForSlot(int slot, ItemStack stack) {
            if (stack.getItem() instanceof ICraftingPatternItem) {
                IGridNode n = getGridNode();
                World w;
                if (n == null) {
                    w = getClientWorld();
                } else {
                    w = n.getWorld();
                }
                if (w == null) return false;
                ICraftingPatternDetails details = ((ICraftingPatternItem) stack.getItem()).getPatternForItem(stack, w);
                return details != null;
            }
            return false;
        }

        @Override
        public boolean isUseableByPlayer(EntityPlayer player) {
            return PartFluidInterface.this.isValid();
        }

        @Override
        public void markDirty() {}

        @Override
        public void openInventory() {}

        public void readFromNBT(NBTTagCompound tagCompound) {

            NBTTagList tagList = tagCompound.getTagList("Inventory", 10);
            for (int i = 0; i < tagList.tagCount(); i++) {
                NBTTagCompound tag = tagList.getCompoundTagAt(i);
                byte slot = tag.getByte("Slot");
                if (slot >= 0 && slot < this.inv.length) {
                    this.inv[slot] = ItemStack.loadItemStackFromNBT(tag);
                }
            }
        }

        @Override
        public void setInventorySlotContents(int slot, ItemStack stack) {
            this.inv[slot] = stack;
            if (stack != null && stack.stackSize > getInventoryStackLimit()) {
                stack.stackSize = getInventoryStackLimit();
            }
            PartFluidInterface.this.originalPatternsCache[slot] = null;
            PartFluidInterface.this.update = true;
        }

        public void writeToNBT(NBTTagCompound tagCompound) {
            NBTTagList itemList = new NBTTagList();
            for (int i = 0; i < this.inv.length; i++) {
                ItemStack stack = this.inv[i];
                if (stack != null) {
                    NBTTagCompound tag = new NBTTagCompound();
                    tag.setByte("Slot", (byte) i);
                    stack.writeToNBT(tag);
                    itemList.appendTag(tag);
                }
            }
            tagCompound.setTag("Inventory", itemList);
        }
    }

    public NBTTagCompound writeFilter(NBTTagCompound tag) {
        if (FluidRegistry.getFluid(this.fluidFilter) == null) return null;
        tag.setInteger("filter", this.fluidFilter);
        return tag;
    }

    private NBTTagCompound writeOutputToNBT(NBTTagCompound tag) {
        int i = 0;
        for (IAEStack s : this.removeFromExport) {
            if (s != null) {
                tag.setBoolean("remove-" + i + "-isItem", s.isItem());
                NBTTagCompound data = new NBTTagCompound();
                if (s.isItem()) {
                    ((IAEItemStack) s).getItemStack().writeToNBT(data);
                } else {
                    ((IAEFluidStack) s).getFluidStack().writeToNBT(data);
                }
                tag.setTag("remove-" + i, data);
                tag.setLong("remove-" + i + "-amount", s.getStackSize());
            }
            i++;
        }
        tag.setInteger("remove", this.removeFromExport.size());
        i = 0;
        for (IAEStack s : this.addToExport) {
            if (s != null) {
                tag.setBoolean("add-" + i + "-isItem", s.isItem());
                NBTTagCompound data = new NBTTagCompound();
                if (s.isItem()) {
                    ((IAEItemStack) s).getItemStack().writeToNBT(data);
                } else {
                    ((IAEFluidStack) s).getFluidStack().writeToNBT(data);
                }
                tag.setTag("add-" + i, data);
                tag.setLong("add-" + i + "-amount", s.getStackSize());
            }
            i++;
        }
        tag.setInteger("add", this.addToExport.size());
        i = 0;
        for (IAEStack s : this.export) {
            if (s != null) {
                tag.setBoolean("export-" + i + "-isItem", s.isItem());
                NBTTagCompound data = new NBTTagCompound();
                if (s.isItem()) {
                    ((IAEItemStack) s).getItemStack().writeToNBT(data);
                } else {
                    ((IAEFluidStack) s).getFluidStack().writeToNBT(data);
                }
                tag.setTag("export-" + i, data);
                tag.setLong("export-" + i + "-amount", s.getStackSize());
            }
            i++;
        }
        tag.setInteger("export", this.export.size());
        return tag;
    }

    @Override
    public void writeToNBT(NBTTagCompound data) {
        writeToNBTWithoutExport(data);
        NBTTagCompound tag = new NBTTagCompound();
        writeOutputToNBT(tag);
        data.setTag("export", tag);
    }

    public void writeToNBTWithoutExport(NBTTagCompound data) {
        super.writeToNBT(data);
        data.setTag("tank", this.tank.writeToNBT(new NBTTagCompound()));
        data.setInteger("filter", this.fluidFilter);
        NBTTagCompound inventory = new NBTTagCompound();
        this.inventory.writeToNBT(inventory);
        data.setTag("inventory", inventory);
    }

    @Override
    public void writeToStream(ByteBuf data) throws IOException {
        super.writeToStream(data);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("tank", this.tank.writeToNBT(new NBTTagCompound()));
        tag.setInteger("filter", this.fluidFilter);
        NBTTagCompound inventory = new NBTTagCompound();
        this.inventory.writeToNBT(inventory);
        tag.setTag("inventory", inventory);
        ByteBufUtils.writeTag(data, tag);
    }
}
