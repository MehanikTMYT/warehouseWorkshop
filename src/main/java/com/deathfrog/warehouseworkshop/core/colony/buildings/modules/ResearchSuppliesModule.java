package com.deathfrog.warehouseworkshop.core.colony.buildings.modules;

import com.deathfrog.warehouseworkshop.WarehouseWorkshopMod;
import com.deathfrog.warehouseworkshop.api.util.TraceUtils;
import com.deathfrog.warehouseworkshop.core.research.ResearchSupplyPlanner;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModule;
import com.minecolonies.api.colony.buildings.modules.IPersistentModule;
import com.minecolonies.api.colony.buildings.modules.ITickingModule;
import com.minecolonies.api.colony.requestsystem.StandardFactoryController;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.research.IGlobalResearch;
import com.minecolonies.api.research.IGlobalResearchTree;
import com.minecolonies.api.research.ILocalResearchTree;
import com.minecolonies.api.util.MessageUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Persists research supply batches and starts opted-in research without requiring an online player. */
public class ResearchSuppliesModule extends AbstractBuildingModule implements IPersistentModule, ITickingModule
{
    private static final String TAG_PENDING = "ResearchSupplyPending";
    private static final String TAG_RESEARCH = "Research";
    private static final String TAG_BRANCH = "Branch";
    private static final String TAG_AUTO_START = "AutoStart";
    private static final String TAG_REQUEST_TOKENS = "RequestTokens";
    private static final String TAG_ORPHAN_TICKS = "OrphanTicks";
    private static final int ORPHAN_GRACE_TICKS = 2;
    private final Map<Identifier, PendingResearch> pending = new LinkedHashMap<>();

    /** Adds or replaces a pending supply batch. */
    public void addPending(final Identifier branch, final Identifier research, final boolean autoStart,
                           final List<IToken<?>> requestTokens)
    {
        pending.put(research, new PendingResearch(branch, research, autoStart, List.copyOf(requestTokens), 0));
        markDirty();
        TraceUtils.dynamicTrace(TraceUtils.TRACE_RESEARCH_DELIVERY, () -> WarehouseWorkshopMod.LOGGER.info(
            "Research supplies: registered pending research branch={}, research={}, autoStart={}, university={}, colony={}",
            branch, research, autoStart, building.getPosition(), building.getColony().getID()));
    }

    /** Returns whether a research already has a supply batch. */
    public boolean isPending(final Identifier research)
    {
        return pending.containsKey(research);
    }

    /** Returns identifiers for all supply batches. */
    public Set<Identifier> getPendingResearch()
    {
        return Set.copyOf(pending.keySet());
    }

    /** Returns identifiers for batches that should automatically start. */
    @SuppressWarnings("null")
    public Set<Identifier> getAutoStartResearch()
    {
        return pending.values().stream().filter(PendingResearch::autoStart).map(PendingResearch::research)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Periodically completes delivered batches and processes the FIFO automatic-start queue. */
    @Override
    public void onColonyTick(@NotNull final IColony colony)
    {
        if (pending.isEmpty())
        {
            return;
        }

        TraceUtils.dynamicTrace(TraceUtils.TRACE_RESEARCH_DELIVERY, () -> WarehouseWorkshopMod.LOGGER.info(
            "Research supplies tick: university={}, colony={}, pending={}, buildingLevel={}, activeResearch={}",
            building.getPosition(), colony.getID(), pending.size(), building.getBuildingLevel(),
            colony.getResearchManager().getResearchTree().getResearchInProgress().size()));

        final List<Identifier> completed = new ArrayList<>();
        for (final PendingResearch entry : pending.values())
        {
            final IGlobalResearch research = IGlobalResearchTree.getInstance().getResearch(entry.branch(), entry.research());
            if (research == null)
            {
                WarehouseWorkshopMod.LOGGER.warn(
                    "Research supplies: removing unresolved research branch={}, research={}, university={}, colony={}",
                    entry.branch(), entry.research(), building.getPosition(), colony.getID());
                completed.add(entry.research());
                continue;
            }
            if (colony.getResearchManager().getResearchTree().getResearch(entry.branch(), entry.research()) != null)
            {
                TraceUtils.dynamicTrace(TraceUtils.TRACE_RESEARCH_DELIVERY, () -> WarehouseWorkshopMod.LOGGER.info(
                    "Research supplies: removing research already present in local tree branch={}, research={}, university={}, colony={}",
                    entry.branch(), entry.research(), building.getPosition(), colony.getID()));
                completed.add(entry.research());
                continue;
            }
            final boolean hasCosts = ResearchSupplyPlanner.universityHasCosts(building, research);

            TraceUtils.dynamicTrace(TraceUtils.TRACE_RESEARCH_DELIVERY, () -> WarehouseWorkshopMod.LOGGER.info(
                "Research supplies entry: branch={}, research={}, autoStart={}, universityHasCosts={}",
                entry.branch(), entry.research(), entry.autoStart(), hasCosts));

            if (!hasCosts)
            {
                if (hasActiveDelivery(colony, entry))
                {
                    if (entry.orphanTicks() != 0)
                    {
                        pending.put(entry.research(), entry.withOrphanTicks(0));
                        markDirty();
                    }
                    continue;
                }

                final int orphanTicks = entry.orphanTicks() + 1;
                if (orphanTicks < ORPHAN_GRACE_TICKS)
                {
                    pending.put(entry.research(), entry.withOrphanTicks(orphanTicks));
                    markDirty();
                    continue;
                }
                completed.add(entry.research());
                final TranslatableContents researchName = research.getName();

                if (researchName == null) continue;

                MessageUtils.format("com.warehouseworkshop.research_supplies.delivery_interrupted",
                    MutableComponent.create(researchName)).sendTo(colony).forManagers();
                continue;
            }

            if (!entry.autoStart())
            {
                TraceUtils.dynamicTrace(TraceUtils.TRACE_RESEARCH_DELIVERY, () -> WarehouseWorkshopMod.LOGGER.info(
                    "Research supplies: delivery-only request completed for branch={}, research={}, university={}, colony={}",
                    entry.branch(), entry.research(), building.getPosition(), colony.getID()));
                completed.add(entry.research());
                continue;
            }
            if (tryStart(colony, research))
            {
                completed.add(entry.research());
                break;
            }
        }
        if (!completed.isEmpty())
        {
            completed.forEach(pending::remove);
            markDirty();
            TraceUtils.dynamicTrace(TraceUtils.TRACE_RESEARCH_DELIVERY, () -> WarehouseWorkshopMod.LOGGER.info(
                "Research supplies: removed completed entries {} from university={}, colony={}; remaining={}",
                completed, building.getPosition(), colony.getID(), pending.size()));
        }
    }

    /** Returns whether any delivery request owned by this pending batch is still being processed. */
    private static boolean hasActiveDelivery(final IColony colony, final PendingResearch entry)
    {
        for (final IToken<?> token : entry.requestTokens())
        {
            final IRequest<?> request = colony.getRequestManager().getRequestForToken(token);
            if (request != null && switch (request.getState())
            {
                case COMPLETED, OVERRULED, CANCELLED, RECEIVED, FAILED -> false;
                default -> true;
            })
            {
                return true;
            }
        }
        return false;
    }

    /** Revalidates, logs each start condition, consumes university-only costs, and starts one research. */
    @SuppressWarnings("null")
    private boolean tryStart(final IColony colony, final IGlobalResearch research)
    {
        final ILocalResearchTree localTree = colony.getResearchManager().getResearchTree();
        final int activeResearch = localTree.getResearchInProgress().size();
        final int researchCapacity = building.getBuildingLevel();
        final boolean queueAvailable = activeResearch < researchCapacity;
        final boolean canResearch = research.canResearch(building, localTree);
        final boolean requirementsFulfilled = IGlobalResearchTree.getInstance()
            .isResearchRequirementsFulfilled(research.getResearchRequirements(), colony);
        final boolean universityHasCosts = ResearchSupplyPlanner.universityHasCosts(building, research);

        TraceUtils.dynamicTrace(TraceUtils.TRACE_RESEARCH_DELIVERY, () -> WarehouseWorkshopMod.LOGGER.info(
            "Research supplies start check: branch={}, research={}, queueAvailable={} ({}/{}), canResearch={}, requirementsFulfilled={}, universityHasCosts={}",
            research.getBranch(), research.getId(), queueAvailable, activeResearch, researchCapacity, canResearch,
            requirementsFulfilled, universityHasCosts));

        if (!queueAvailable || !canResearch || !requirementsFulfilled || !universityHasCosts) return false;

        TraceUtils.dynamicTrace(TraceUtils.TRACE_RESEARCH_DELIVERY, () -> WarehouseWorkshopMod.LOGGER.info(
            "Research supplies: invoking startResearch for branch={}, research={}, university={}, colony={}",
            research.getBranch(), research.getId(), building.getPosition(), colony.getID()));
        try
        {
            research.startResearch(localTree);
        }
        catch (final RuntimeException exception)
        {
            final boolean registered = localTree.getResearch(research.getBranch(), research.getId()) != null;
            WarehouseWorkshopMod.LOGGER.error(
                "Research supplies: startResearch threw for branch={}, research={}, registered={}, university={}, colony={}",
                research.getBranch(), research.getId(), registered, building.getPosition(), colony.getID(), exception);
            if (!registered) return false;
        }
        final boolean registered = localTree.getResearch(research.getBranch(), research.getId()) != null;
        final int activeAfterStart = localTree.getResearchInProgress().size();
        if (!registered)
        {
            WarehouseWorkshopMod.LOGGER.warn(
                "Research supplies: startResearch returned but research is absent from the local tree; branch={}, research={}, activeBefore={}, activeAfter={}, university={}, colony={}",
                research.getBranch(), research.getId(), activeResearch, activeAfterStart, building.getPosition(), colony.getID());
            return false;
        }
        else
        {
            TraceUtils.dynamicTrace(TraceUtils.TRACE_RESEARCH_DELIVERY, () -> WarehouseWorkshopMod.LOGGER.info(
                "Research supplies: startResearch registered successfully; branch={}, research={}, activeBefore={}, activeAfter={}, university={}, colony={}",
                research.getBranch(), research.getId(), activeResearch, activeAfterStart, building.getPosition(), colony.getID()));
        }
        if (!ResearchSupplyPlanner.consumeUniversityCosts(building, research))
        {
            WarehouseWorkshopMod.LOGGER.error(
                "Research supplies: research registered but its validated costs could not be consumed; branch={}, research={}, university={}, colony={}",
                research.getBranch(), research.getId(), building.getPosition(), colony.getID());
        }
        colony.getResearchManager().markDirty();
        colony.markDirty();
        MessageUtils.format("com.warehouseworkshop.research_supplies.started",
            MutableComponent.create(research.getName())).sendTo(colony).forManagers();
        return true;
    }

    /** Loads persistent supply batches. */
    @SuppressWarnings("null")
    @Override
    public void deserializeNBT(@NotNull final HolderLookup.Provider provider, @NotNull final CompoundTag compound)
    {
        pending.clear();
        for (final Tag raw : compound.getListOrEmpty(TAG_PENDING))
        {
            final CompoundTag tag = (CompoundTag) raw;
            final Identifier branch = Identifier.tryParse(tag.getStringOr(TAG_BRANCH, ""));
            final Identifier research = Identifier.tryParse(tag.getStringOr(TAG_RESEARCH, ""));
            if (branch != null && research != null)
            {
                final List<IToken<?>> requestTokens = new ArrayList<>();
                for (final Tag tokenTag : tag.getListOrEmpty(TAG_REQUEST_TOKENS))
                {
                    requestTokens.add(StandardFactoryController.getInstance().deserializeTag(provider, (CompoundTag) tokenTag));
                }
                pending.put(research, new PendingResearch(branch, research, tag.getBooleanOr(TAG_AUTO_START, false),
                    List.copyOf(requestTokens), tag.getIntOr(TAG_ORPHAN_TICKS, 0)));
            }
        }
        if (!pending.isEmpty())
        {
            TraceUtils.dynamicTrace(TraceUtils.TRACE_RESEARCH_DELIVERY, () -> WarehouseWorkshopMod.LOGGER.info(
                "Research supplies: restored {} pending entries from NBT for university={}, colony={}",
                pending.size(), building.getPosition(), building.getColony().getID()));
        }
    }

    /** Saves persistent supply batches. */
    @SuppressWarnings("null")
    @Override
    public void serializeNBT(@NotNull final HolderLookup.Provider provider, @NotNull final CompoundTag compound)
    {
        final ListTag list = new ListTag();
        for (final PendingResearch entry : pending.values())
        {
            final CompoundTag tag = new CompoundTag();
            tag.put(TAG_BRANCH, StringTag.valueOf(entry.branch().toString()));
            tag.put(TAG_RESEARCH, StringTag.valueOf(entry.research().toString()));
            tag.putBoolean(TAG_AUTO_START, entry.autoStart());
            final ListTag requestTokens = new ListTag();
            for (final IToken<?> token : entry.requestTokens())
            {
                requestTokens.add(StandardFactoryController.getInstance().serializeTag(provider, token));
            }
            tag.put(TAG_REQUEST_TOKENS, requestTokens);
            tag.putInt(TAG_ORPHAN_TICKS, entry.orphanTicks());
            list.add(tag);
        }
        compound.put(TAG_PENDING, list);
    }

    /** Persistent descriptor for one requested research. */
    private record PendingResearch(Identifier branch, Identifier research, boolean autoStart,
                                   List<IToken<?>> requestTokens, int orphanTicks)
    {
        private PendingResearch withOrphanTicks(final int ticks)
        {
            return new PendingResearch(branch, research, autoStart, requestTokens, ticks);
        }
    }
}
