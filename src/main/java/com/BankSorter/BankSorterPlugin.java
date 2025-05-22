package com.BankSorter;

import java.util.*;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.api.gameval.*;

@Slf4j
@PluginDescriptor(
		name = "Bank Tab Sorter",
		description = "Visually sorts items in your active bank tab by GE value",
		tags = {"bank", "tab", "sort", "grand exchange", "value", "gp", "visual"}
)
public class BankSorterPlugin extends Plugin {

	private static final String SORT_BY_GE_VALUE = "Sort items by GE value";
	private static final String RESTORE_ORDER = "Restore original order";

	private static final List<Integer> TAB_VARBITIDS = Arrays.asList(
			VarbitID.BANK_TAB_1, VarbitID.BANK_TAB_2, VarbitID.BANK_TAB_3,
			VarbitID.BANK_TAB_4, VarbitID.BANK_TAB_5, VarbitID.BANK_TAB_6,
			VarbitID.BANK_TAB_7, VarbitID.BANK_TAB_8, VarbitID.BANK_TAB_9
	);

	@Inject
	private Client client;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ClientThread clientThread;

	// State tracking
	private boolean isSortingActive = false;
	private final Map<Integer, WidgetPosition> originalPositions = new HashMap<>();
	private List<BankItemValue> currentSortedItems = new ArrayList<>();
	private int lastActiveTab = -1;

	@Override
	protected void startUp() {
		log.info("Bank Visual Sorter plugin started");
	}

	@Override
	protected void shutDown() {
		log.info("Bank Visual Sorter plugin stopped");
		restoreOriginalOrder();
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event) {
		if (isBankClosed()) {
			return;
		}

		if (!isBankSettingsButton(event)) {
			return;
		}

		// Add sort option
		client.getMenu().createMenuEntry(-1)
				.setOption(SORT_BY_GE_VALUE)
				.setTarget("")
				.setType(MenuAction.RUNELITE)
				.setIdentifier(0)
				.onClick(this::sortTabByGEValue);

		// Add restore option if currently sorted
		if (isSortingActive) {
			client.getMenu().createMenuEntry(-1)
					.setOption(RESTORE_ORDER)
					.setTarget("")
					.setType(MenuAction.RUNELITE)
					.setIdentifier(1)
					.onClick(this::restoreOrder);
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event) {
		if (event.getGroupId() == InterfaceID.BANKMAIN) {
			// Bank closed, clean up
			isSortingActive = false;
			originalPositions.clear();
			currentSortedItems.clear();
		}
	}

	@Subscribe
	public void onClientTick(ClientTick clientTick) {
		if (isBankClosed()) {
			return;
		}

		int currentTab = client.getVarbitValue(VarbitID.BANK_CURRENTTAB);

		// If tab changed, restore original order and clear sorting
		if (lastActiveTab != -1 && lastActiveTab != currentTab && isSortingActive) {
			restoreOriginalOrder();
		}

		lastActiveTab = currentTab;

		// Apply sorting if active
		if (isSortingActive && !currentSortedItems.isEmpty()) {
			applySortingToWidgets();
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event) {
		if (event.getContainerId() == InventoryID.BANK && isSortingActive) {
			clientThread.invoke(() -> {
				Item[] items = getBankTabItems();
				if (items != null && items.length > 0) {
					recalculateSorting(items);
				}
			});
		}
	}

	private void sortTabByGEValue(MenuEntry entry) {
		log.info("Starting visual sort of bank tab");

		Item[] items = getBankTabItems();
		if (items == null || items.length == 0) {
			log.info("No items to sort in this tab");
			return;
		}

		// Store original positions before sorting
		storeOriginalPositions();

		// Calculate sorted order
		List<BankItemValue> itemValues = new ArrayList<>();
		int position = 0;

		for (Item item : items) {
			if (item == null || item.getId() <= 0) {
				position++;
				continue;
			}

			int gePrice = itemManager.getItemPrice(item.getId());
			long totalValue = (long) gePrice * item.getQuantity();

			itemValues.add(new BankItemValue(position, item.getId(), totalValue, item.getQuantity()));
			position++;
		}

		itemValues.sort(Comparator.comparingLong(BankItemValue::getTotalValue).reversed());
		currentSortedItems = itemValues;
		isSortingActive = true;

		log.info("Visual sort applied: {} items rearranged", itemValues.size());

		// The actual widget rearrangement will happen in onClientTick
	}

	private void recalculateSorting(Item[] items) {
		// Recalculate sorted order without storing original positions again
		List<BankItemValue> itemValues = new ArrayList<>();
		int position = 0;

		for (Item item : items) {
			if (item == null || item.getId() <= 0) {
				position++;
				continue;
			}

			int gePrice = itemManager.getItemPrice(item.getId());
			long totalValue = (long) gePrice * item.getQuantity();

			itemValues.add(new BankItemValue(position, item.getId(), totalValue, item.getQuantity()));
			position++;
		}

		itemValues.sort(Comparator.comparingLong(BankItemValue::getTotalValue).reversed());
		currentSortedItems = itemValues;

		log.debug("Recalculated sorting: {} items", itemValues.size());
	}

	private void restoreOrder(MenuEntry entry) {
		restoreOriginalOrder();
	}

	private void restoreOriginalOrder() {
		if (!isSortingActive || originalPositions.isEmpty()) {
			return;
		}

		Widget bankContainer = client.getWidget(InterfaceID.BANKMAIN, 13);
		if (bankContainer == null) {
			return;
		}

		Widget[] widgets = bankContainer.getDynamicChildren();
		for (int i = 0; i < widgets.length; i++) {
			Widget widget = widgets[i];
			if (widget.getItemId() > 0) {
				WidgetPosition originalPos = originalPositions.get(i);
				if (originalPos != null) {
					widget.setOriginalX(originalPos.x);
					widget.setOriginalY(originalPos.y);
					widget.revalidate();
				}
			}
		}

		isSortingActive = false;
		originalPositions.clear();
		currentSortedItems.clear();

		log.info("Original bank order restored");
	}

	private void storeOriginalPositions() {
		originalPositions.clear();

		Widget bankContainer = client.getWidget(InterfaceID.BANKMAIN, 13);
		if (bankContainer == null) {
			return;
		}

		Widget[] widgets = bankContainer.getDynamicChildren();
		for (int i = 0; i < widgets.length; i++) {
			Widget widget = widgets[i];
			if (widget.getItemId() > 0) {
				// Store by array index instead of widget ID
				originalPositions.put(i, new WidgetPosition(widget.getOriginalX(), widget.getOriginalY()));
			}
		}

		log.debug("Stored original positions for {} widgets", originalPositions.size());
	}

	private void applySortingToWidgets() {
		Widget bankContainer = client.getWidget(InterfaceID.BANKMAIN, 13);
		if (bankContainer == null) {
			return;
		}

		// Get current bank items and their widgets
		Item[] bankItems = getBankTabItems();
		if (bankItems == null) {
			return;
		}

		Widget[] itemWidgets = bankContainer.getDynamicChildren();
		if (itemWidgets == null) {
			return;
		}

		// Calculate grid layout constants
		final int ITEMS_PER_ROW = 8;
		final int ITEM_WIDTH = 48;
		final int ITEM_HEIGHT = 36;
		final int START_X = 51; // Bank container offset
		final int START_Y = 2;

		// Create mapping of item IDs to widgets
		Map<Integer, List<Widget>> itemIdToWidgets = new HashMap<>();
		for (Widget widget : itemWidgets) {
			if (widget.getItemId() > 0) {
				itemIdToWidgets.computeIfAbsent(widget.getItemId(), k -> new ArrayList<>()).add(widget);
			}
		}

		// Apply new positions based on sorted order
		int newPosition = 0;
		for (BankItemValue sortedItem : currentSortedItems) {
			List<Widget> widgets = itemIdToWidgets.get(sortedItem.getItemId());
			if (widgets != null && !widgets.isEmpty()) {
				// Use the first widget found for this item ID
				Widget widget = widgets.get(0);

				// Calculate new position
				int row = newPosition / ITEMS_PER_ROW;
				int col = newPosition % ITEMS_PER_ROW;
				int newX = START_X + (col * ITEM_WIDTH);
				int newY = START_Y + (row * ITEM_HEIGHT);

				// Apply new position
				widget.setOriginalX(newX);
				widget.setOriginalY(newY);
				widget.revalidate();

				newPosition++;
			}
		}
	}

	private boolean isBankClosed() {
		Widget bankWidget = client.getWidget(InterfaceID.BANKMAIN, 0);
		return bankWidget == null || bankWidget.isHidden();
	}

	private boolean isBankSettingsButton(MenuEntryAdded event) {
		return "Show menu".equals(event.getOption());
	}

	private Item[] getBankTabItems() {
		ItemContainer bankContainer = client.getItemContainer(InventoryID.BANK);
		if (bankContainer == null) {
			return null;
		}

		Item[] items = bankContainer.getItems();
		int activeTab = client.getVarbitValue(VarbitID.BANK_CURRENTTAB);

		if (activeTab == 0) {
			return items; // All items tab
		}

		int tabIndex = activeTab - 1;
		if (tabIndex < 0 || tabIndex >= TAB_VARBITIDS.size()) {
			return items;
		}

		int startIndex = 0;
		for (int i = 0; i < tabIndex; i++) {
			int tabItemCount = client.getVarbitValue(TAB_VARBITIDS.get(i));
			startIndex += tabItemCount;
		}

		int itemCount = client.getVarbitValue(TAB_VARBITIDS.get(tabIndex));
		int endIndex = Math.min(startIndex + itemCount, items.length);

		if (startIndex >= items.length) {
			return new Item[0];
		}

		return Arrays.copyOfRange(items, startIndex, endIndex);
	}

	// Helper class to store widget positions
	private static class WidgetPosition {
		final int x, y;

		WidgetPosition(int x, int y) {
			this.x = x;
			this.y = y;
		}
	}
}