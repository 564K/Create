package com.simibubi.create.content.curiosities.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import com.simibubi.create.content.curiosities.tools.BlueprintEntity.BlueprintCraftingInventory;
import com.simibubi.create.content.curiosities.tools.BlueprintEntity.BlueprintSection;
import com.simibubi.create.content.logistics.item.filter.FilterItem;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.gui.GuiGameElement;
import com.simibubi.create.foundation.utility.AnimationTickHolder;
import com.simibubi.create.foundation.utility.Pair;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.inventory.CraftingInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.crafting.ICraftingRecipe;
import net.minecraft.item.crafting.IRecipeType;
import net.minecraft.util.math.EntityRayTraceResult;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.RayTraceResult.Type;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.ItemStackHandler;

public class BlueprintOverlayRenderer {

	static boolean active;
	static boolean empty;
	static boolean lastSneakState;
	static BlueprintSection lastTargetedSection;

	static List<Pair<ItemStack, Boolean>> ingredients = new ArrayList<>();
	static ItemStack result = ItemStack.EMPTY;
	static boolean resultCraftable = false;

	public static void tick() {
		Minecraft mc = Minecraft.getInstance();
		RayTraceResult mouseOver = mc.objectMouseOver;
		BlueprintSection last = lastTargetedSection;
		boolean sneak = mc.player.isSneaking();
		lastTargetedSection = null;
		active = false;
		if (mouseOver == null)
			return;
		if (mouseOver.getType() != Type.ENTITY)
			return;

		EntityRayTraceResult entityRay = (EntityRayTraceResult) mouseOver;
		if (!(entityRay.getEntity() instanceof BlueprintEntity))
			return;

		BlueprintEntity blueprintEntity = (BlueprintEntity) entityRay.getEntity();
		BlueprintSection sectionAt = blueprintEntity.getSectionAt(entityRay.getHitVec()
			.subtract(blueprintEntity.getPositionVec()));

		lastTargetedSection = last;
		active = true;

		if (sectionAt != lastTargetedSection || AnimationTickHolder.getTicks() % 10 == 0 || lastSneakState != sneak)
			rebuild(sectionAt, sneak);

		lastTargetedSection = sectionAt;
		lastSneakState = sneak;
	}

	public static void rebuild(BlueprintSection sectionAt, boolean sneak) {
		ItemStackHandler items = sectionAt.getItems();
		boolean empty = true;
		for (int i = 0; i < 9; i++) {
			if (!items.getStackInSlot(i)
				.isEmpty()) {
				empty = false;
				break;
			}
		}

		BlueprintOverlayRenderer.empty = empty;
		BlueprintOverlayRenderer.result = ItemStack.EMPTY;

		if (empty)
			return;

		boolean firstPass = true;
		boolean success = true;
		Minecraft mc = Minecraft.getInstance();
		ItemStackHandler playerInv = new ItemStackHandler(mc.player.inventory.getSizeInventory());
		for (int i = 0; i < playerInv.getSlots(); i++)
			playerInv.setStackInSlot(i, mc.player.inventory.getStackInSlot(i)
				.copy());

		int amountCrafted = 0;
		Optional<ICraftingRecipe> recipe = Optional.empty();
		Map<Integer, ItemStack> craftingGrid = new HashMap<>();
		ingredients.clear();
		ItemStackHandler missingItems = new ItemStackHandler(64);
		ItemStackHandler availableItems = new ItemStackHandler(64);
		List<ItemStack> newlyAdded = new ArrayList<>();
		List<ItemStack> newlyMissing = new ArrayList<>();
		boolean invalid = false;

		do {
			craftingGrid.clear();
			newlyAdded.clear();
			newlyMissing.clear();

			Search: for (int i = 0; i < 9; i++) {
				ItemStack requestedItem = items.getStackInSlot(i);
				if (requestedItem.isEmpty()) {
					craftingGrid.put(i, ItemStack.EMPTY);
					continue;
				}

				for (int slot = 0; slot < playerInv.getSlots(); slot++) {
					if (!FilterItem.test(mc.world, playerInv.getStackInSlot(slot), requestedItem))
						continue;
					ItemStack currentItem = playerInv.extractItem(slot, 1, false);
					craftingGrid.put(i, currentItem);
					newlyAdded.add(currentItem);
					continue Search;
				}

				success = false;
				newlyMissing.add(requestedItem);
			}

			if (success) {
				CraftingInventory craftingInventory = new BlueprintCraftingInventory(craftingGrid);
				if (!recipe.isPresent())
					recipe = mc.world.getRecipeManager()
						.getRecipe(IRecipeType.CRAFTING, craftingInventory, mc.world);
				ItemStack resultFromRecipe = recipe.filter(r -> r.matches(craftingInventory, mc.world))
					.map(r -> r.getCraftingResult(craftingInventory))
					.orElse(ItemStack.EMPTY);

				if (resultFromRecipe.isEmpty()) {
					if (!recipe.isPresent())
						invalid = true;
					success = false;
				} else if (resultFromRecipe.getCount() + amountCrafted > 64) {
					success = false;
				} else {
					amountCrafted += resultFromRecipe.getCount();
					if (result.isEmpty())
						result = resultFromRecipe.copy();
					else
						result.grow(resultFromRecipe.getCount());
					resultCraftable = true;
					firstPass = false;
				}
			}

			if (success || firstPass) {
				newlyAdded.forEach(s -> ItemHandlerHelper.insertItemStacked(availableItems, s, false));
				newlyMissing.forEach(s -> ItemHandlerHelper.insertItemStacked(missingItems, s, false));
			}

			if (!success) {
				if (firstPass) {
					result = invalid ? ItemStack.EMPTY : items.getStackInSlot(9);
					resultCraftable = false;
				}
				break;
			}

			if (!sneak)
				break;

		} while (success);

		for (int i = 0; i < 9; i++) {
			ItemStack available = availableItems.getStackInSlot(i);
			if (available.isEmpty())
				continue;
			ingredients.add(Pair.of(available, true));
		}
		for (int i = 0; i < 9; i++) {
			ItemStack missing = missingItems.getStackInSlot(i);
			if (missing.isEmpty())
				continue;
			ingredients.add(Pair.of(missing, false));
		}
	}

	public static void renderOverlay(MatrixStack ms, IRenderTypeBuffer buffer, int light, int overlay,
		float partialTicks) {
		if (!active || empty)
			return;

		Minecraft mc = Minecraft.getInstance();
		int w = 30 + 21 * ingredients.size() + 21;

		int x = (mc.getWindow()
			.getScaledWidth() - w) / 2;
		int y = (int) (mc.getWindow()
			.getScaledHeight() / 3f * 2);

		for (Pair<ItemStack, Boolean> pair : ingredients) {
			RenderSystem.enableBlend();
			(pair.getSecond() ? AllGuiTextures.HOTSLOT_ACTIVE : AllGuiTextures.HOTSLOT).draw(ms, x, y);
			ItemStack itemStack = pair.getFirst();
			GuiGameElement.of(itemStack)
				.at(x + 3, y + 3)
				.render(ms);
			mc.getItemRenderer()
				.renderItemOverlayIntoGUI(mc.fontRenderer, itemStack, x + 3, y + 3,
					pair.getSecond() || itemStack.getCount() == 1 ? null
						: TextFormatting.GOLD.toString() + itemStack.getCount());
			x += 21;
		}

		x += 5;
		RenderSystem.enableBlend();
		AllGuiTextures.HOTSLOT_ARROW.draw(ms, x, y + 4);
		x += 25;

		if (result.isEmpty()) {
			AllGuiTextures.HOTSLOT.draw(ms, x, y);
			GuiGameElement.of(Items.BARRIER)
				.at(x + 3, y + 3)
				.render(ms);
		} else {
			(resultCraftable ? AllGuiTextures.HOTSLOT_SUPER_ACTIVE : AllGuiTextures.HOTSLOT).draw(ms,
				resultCraftable ? x - 1 : x, resultCraftable ? y - 1 : y);
			GuiGameElement.of(result)
				.at(x + 3, y + 3)
				.render(ms);
			mc.getItemRenderer()
				.renderItemOverlayIntoGUI(mc.fontRenderer, result, x + 3, y + 3, null);
		}
	}

}
