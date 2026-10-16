package com.deathfrog.warehouseworkshop.core.items;

import net.minecraft.world.item.component.TooltipDisplay;
import java.util.function.Consumer;
import com.deathfrog.warehouseworkshop.core.client.ResearchSuppliesClientHooks;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingUniversity;
import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import javax.annotation.Nonnull;

/** Portable ledger bound to a specific MineColonies university. */
public class ItemResearchLedger extends Item
{
    private static final String TAG_UNIVERSITY = "University";
    private static final String TAG_DIMENSION = "Dimension";
    private static final String TAG_COLONY = "Colony";

    /** Creates a non-stackable Research Ledger. */
    public ItemResearchLedger(final Item.Properties properties)
    {
        super(properties.stacksTo(1));
    }

    /** Sneak-clicks a university to bind its position to this ledger. */
    @SuppressWarnings("null")
    @Override
    public @NotNull InteractionResult useOn(final @Nonnull UseOnContext context)
    {
        if (!context.getPlayer().isShiftKeyDown())
        {
            return InteractionResult.PASS;
        }
        if (context.getLevel().getBlockEntity(context.getClickedPos()) instanceof TileEntityColonyBuilding tile
            && tile.getBuilding() instanceof BuildingUniversity)
        {
            if (!context.getLevel().isClientSide())
            {
                bind(context.getItemInHand(), tile.getBlockPos(), context.getLevel().dimension().identifier(), tile.getColonyId());
                context.getPlayer().sendOverlayMessage(Component.translatable("com.warehouseworkshop.research_ledger.bound"));
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    /** Opens the portable research-supplies window when the ledger is bound. */
    @SuppressWarnings("null")
    @Override
    public @NotNull InteractionResult use(final @Nonnull Level level, final @Nonnull Player player, final @Nonnull InteractionHand hand)
    {
        final ItemStack stack = player.getItemInHand(hand);
        final BlockPos position = getUniversity(stack);
        if (level.isClientSide())
        {
            if (position == null)
            {
                player.sendOverlayMessage(Component.translatable("com.warehouseworkshop.research_ledger.unbound"));
            }
            else if (!level.dimension().identifier().equals(getDimension(stack)))
            {
                player.sendOverlayMessage(Component.translatable("com.warehouseworkshop.research_ledger.wrong_dimension"));
            }
            else
            {
                ResearchSuppliesClientHooks.openLedger(position);
            }
        }
        return InteractionResult.SUCCESS;
    }

    /** Adds binding instructions and current binding state to the item tooltip. */
    @Override
    public void appendHoverText(@NotNull final @Nonnull ItemStack stack, @Nullable final @Nonnull TooltipContext context,
                                @NotNull final @Nonnull TooltipDisplay display, @NotNull final @Nonnull Consumer<Component> tooltip,
                                @NotNull final @Nonnull TooltipFlag flag)
    {
        tooltip.accept(Component.translatable("com.warehouseworkshop.research_ledger.tooltip"));
        final BlockPos position = getUniversity(stack);
        tooltip.accept(position == null
            ? Component.translatable("com.warehouseworkshop.research_ledger.not_bound")
            : Component.translatable("com.warehouseworkshop.research_ledger.bound_to", position.getX(), position.getY(), position.getZ()));
        super.appendHoverText(stack, context, display, tooltip, flag);
    }

    /** Writes a university binding into an item stack. */
    @SuppressWarnings("null")
    private static void bind(final ItemStack stack, final BlockPos position, final Identifier dimension, final int colonyId)
    {
        final CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.putLong(TAG_UNIVERSITY, position.asLong());
        tag.putString(TAG_DIMENSION, dimension.toString());
        tag.putInt(TAG_COLONY, colonyId);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    /** Reads the bound university position, or null when unbound. */
    @SuppressWarnings("null")
    @Nullable
    public static BlockPos getUniversity(final ItemStack stack)
    {
        final CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return tag.contains(TAG_UNIVERSITY) ? BlockPos.of(tag.getLongOr(TAG_UNIVERSITY, 0L)) : null;
    }

    /** Reads the dimension containing the bound university. */
    @SuppressWarnings("null")
    @Nullable
    private static Identifier getDimension(final ItemStack stack)
    {
        final CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return tag.contains(TAG_DIMENSION) ? Identifier.tryParse(tag.getStringOr(TAG_DIMENSION, "")) : null;
    }
}
