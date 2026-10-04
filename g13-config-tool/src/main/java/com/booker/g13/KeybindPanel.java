package com.booker.g13;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.Properties;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.border.TitledBorder;

/**
 * A JPanel for configuring the binding of a single selected key.
 * It allows the user to choose between a passthrough key, a macro, or no assignment.
 */
public class KeybindPanel extends JPanel {

	private static final long serialVersionUID = 1L;

	// --- UI Components for Passthrough Binding ---
	private final JCheckBox passthroughButton = new JCheckBox("Pass Through");
	private final JTextField passthroughText = new JTextField();
	private int passthroughCode = 0; // The Linux keycode for the passthrough key.
	
	// --- UI Components for Macro Binding ---
	private final JCheckBox macroButton = new JCheckBox("Macro");
	private final JComboBox<Properties> macroSelectionBox = new JComboBox<>();
	private final JCheckBox repeatsCheckBox = new JCheckBox("Auto Repeat");
	
	// --- UI Components for the Backlight Color ---
	// The driver uses one color for the whole G13 backlight (screen and keys), stored per binding profile.
	private static final String[] PROFILE_NAMES = { "M1", "M2", "M3", "MR" };
	private final JButton colorChangeButton = new JButton("Change Color");
	private final TitledBorder colorPanelBorder = BorderFactory.createTitledBorder("Backlight Color");
	private Color backlightColor = Color.WHITE;
	
	// --- State Variables ---
	private int bindingsId = -1; // The ID of the currently loaded binding profile (0-3).
	private Properties bindings; // The properties for the current binding profile.
	private Properties[] macros; // All available macros, for the dropdown list.
	private Key key = null; // The currently selected key being edited.
	
	/** A flag to prevent listeners from firing during programmatic data loading. */
	private volatile boolean loadingData = false;
	
	/**
	 * Constructs the KeybindPanel, setting up its UI and event listeners.
	 */
	public KeybindPanel() {
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createTitledBorder("Keybindings Panel"));
		
		setupUI();
        attachListeners();
		
		// Initially, no key is selected, so the panel is disabled.
		setSelectedKey(null);
	}
	
	/**
	 * Creates and arranges all UI components within the panel.
	 */
	private void setupUI() {
		add(createColorPanel(), BorderLayout.NORTH);
		
		final ButtonGroup buttonGroup = new ButtonGroup();
		buttonGroup.add(passthroughButton);
		buttonGroup.add(macroButton);
		
		// Disable focus traversal for the passthrough text field to capture all key events.
		passthroughText.setFocusTraversalKeysEnabled(false);
		
		final JPanel grid = new JPanel(new GridLayout(0, 2, 5, 5)); // Layout with spacing
		grid.add(passthroughButton);
		grid.add(passthroughText);
		grid.add(new JLabel(" ")); // Spacer
		grid.add(new JLabel(" ")); // Spacer
		grid.add(macroButton);
		grid.add(macroSelectionBox);
		grid.add(new JLabel(" ")); // Spacer
		grid.add(repeatsCheckBox);
		
		grid.setBorder(BorderFactory.createTitledBorder("Button Type"));
		
		// Use a custom renderer to display macro names in the combo box.
		macroSelectionBox.setRenderer(new MacroListCellRenderer());
		
		add(grid, BorderLayout.CENTER);
	}

	/**
	 * Attaches all necessary event listeners to the UI components.
	 */
	private void attachListeners() {
		// Use lambda expressions for concise listener implementation.
		macroButton.addActionListener(e -> updateComponentStateAndSave());
		macroSelectionBox.addActionListener(e -> saveBindings());
		repeatsCheckBox.addActionListener(e -> saveBindings());
		passthroughButton.addActionListener(e -> updateComponentStateAndSave());

		passthroughText.addKeyListener(new KeyAdapter() {
			@Override
			public void keyReleased(KeyEvent event) {
				if (loadingData) return;
				loadingData = true; // Prevent re-triggering while updating
				passthroughCode = JavaToLinuxKeymapping.keyEventToCCode(event);
				passthroughText.setText(JavaToLinuxKeymapping.cKeyCodeToString(passthroughCode));
				loadingData = false;
				saveBindings();
			}
		});
	}

    /**
     * A helper method to update the enabled state of components based on radio button selection,
     * and then trigger a save operation.
     */
    private void updateComponentStateAndSave() {
        passthroughText.setEnabled(passthroughButton.isSelected());
        macroSelectionBox.setEnabled(macroButton.isSelected());
        repeatsCheckBox.setEnabled(macroButton.isSelected());
        saveBindings();
    }
	
	/**
	 * Populates the macro selection combo box with the available macros.
	 * @param macros An array of Properties, where each represents a macro.
	 */
	public void setMacros(final Properties[] macros) {
		loadingData = true;
		this.macros = macros;
		
		macroSelectionBox.removeAllItems();
		for (final Properties properties : macros) {
			macroSelectionBox.addItem(properties);
		}
		
		loadingData = false;
	}
	
	/**
	 * Loads a specific binding profile into the panel.
	 * @param propertyNum The ID of the binding profile (0-3).
	 * @param bindings The Properties object for the profile.
	 */
	public void setBindings(final int propertyNum, final Properties bindings) {
		loadingData = true;
		
		this.bindingsId = propertyNum;
		this.bindings = bindings;
		
		// Parse and set the background color from the properties.
		final String val = bindings.getProperty("color", "255,255,255");
		backlightColor = Color.WHITE; // Fallback to white.
		try {
			String[] parts = val.split(",");
			if (parts.length == 3) {
				int r = Integer.parseInt(parts[0].trim());
				int g = Integer.parseInt(parts[1].trim());
				int b = Integer.parseInt(parts[2].trim());
				backlightColor = new Color(r, g, b);
			}
		} catch (IllegalArgumentException e) { // Also covers values outside 0-255.
			System.err.println("Invalid color format in properties: " + val);
		}
		updateColorPanel();
		
		setSelectedKey(null); // Reset selection when bindings change.
		loadingData = false;
	}
	
	/**
	 * Updates the panel's UI to reflect the configuration of the given key.
	 * This is the main method for controlling the panel's state.
	 * @param key The Key object to be edited, or null to disable the panel.
	 */
	public void setSelectedKey(final Key key) {
		this.key = key;
		loadingData = true;
		
		final boolean isKeySelected = (key != null);
		// Enable or disable the key binding controls based on whether a key is selected.
		// The backlight color belongs to the profile, not to a key, so it is always enabled.
		final JComponent[] all = { macroButton, macroSelectionBox, passthroughButton, passthroughText, repeatsCheckBox };
		for (final JComponent c : all) {
			c.setEnabled(isKeySelected);
		}
		
		if (!isKeySelected) {
			loadingData = false;
			return;
		}
		
		// Get the binding string for the selected key, e.g., "p,k.1" or "m,5,1".
		final String propKey = "G" + key.getG13KeyCode();
		final String val = bindings.getProperty(propKey, "p,k.1"); // Default to 'ESC' if unassigned.
		
		String[] parts = val.split("[,.]");
		String type = parts.length > 0 ? parts[0] : "p";
		
		try {
			if ("p".equals(type)) { // Passthrough type
				passthroughButton.setSelected(true);
				passthroughCode = (parts.length >= 3) ? Integer.parseInt(parts[2]) : 1; // Default keycode 1 (ESC).
				passthroughText.setText(JavaToLinuxKeymapping.cKeyCodeToString(passthroughCode));
			} else { // Macro type "m"
				macroButton.setSelected(true);
				int macroNum = (parts.length >= 2) ? Integer.parseInt(parts[1]) : 0;
				macroSelectionBox.setSelectedIndex(macroNum);
				boolean repeats = (parts.length >= 3) && (Integer.parseInt(parts[2]) != 0);
				repeatsCheckBox.setSelected(repeats);
			}
		} catch(NumberFormatException | ArrayIndexOutOfBoundsException e) {
			System.err.println("Failed to parse binding property: " + val);
			passthroughButton.setSelected(true); // Fallback to a safe default.
		}
		
		updateComponentStateAndSave(); // Sync UI component states.
		loadingData = false;
	}
	
	/**
	 * Opens a JColorChooser dialog to change the G13's backlight color for the current profile.
	 */
	private void changeBacklightColor() {
		if (bindings == null) return;
		final Color newColor = JColorChooser.showDialog(this, "Choose Backlight Color (" + profileName() + ")", backlightColor);
		
		if (newColor == null) return; // User cancelled the dialog.
		
		// Store color as an "R,G,B" string.
		bindings.setProperty("color", newColor.getRed() + "," + newColor.getGreen() + "," + newColor.getBlue());
		backlightColor = newColor;
		updateColorPanel();
		
		try {
			Configs.saveBindings(bindingsId, bindings);
		} catch (IOException e) {
			e.printStackTrace();
			JOptionPane.showMessageDialog(this, "Could not save color setting: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
		}
	}
	
	/**
	 * Factory method to create the color selection panel.
	 * @return The configured JPanel for color selection.
	 */
	private JPanel createColorPanel() {
		final JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));
		p.setBorder(colorPanelBorder);
		p.add(colorChangeButton);
		// Show the color as an icon, as not every look and feel paints button backgrounds.
		colorChangeButton.setIcon(new ColorSwatchIcon());
		colorChangeButton.addActionListener(e -> changeBacklightColor());
		return p;
	}
	
	/** @return The name of the current binding profile, e.g. "M1". */
	private String profileName() {
		return bindingsId >= 0 && bindingsId < PROFILE_NAMES.length ? PROFILE_NAMES[bindingsId] : "?";
	}
	
	/**
	 * Updates the color panel title, tooltip and swatch for the current profile.
	 */
	private void updateColorPanel() {
		colorPanelBorder.setTitle("Backlight Color (" + profileName() + ")");
		colorChangeButton.setToolTipText("Color of the G13 backlight (screen and keys) while profile " + profileName()
				+ " is active. Each profile (M1, M2, M3, MR) has its own color.");
		repaint();
	}
	
	/**
	 * A small square icon filled with the current backlight color.
	 */
	private class ColorSwatchIcon implements Icon {
		private static final int SIZE = 16;
		
		@Override
		public void paintIcon(Component c, Graphics g, int x, int y) {
			g.setColor(backlightColor);
			g.fillRect(x, y, SIZE, SIZE);
			g.setColor(Color.GRAY);
			g.drawRect(x, y, SIZE - 1, SIZE - 1);
		}
		
		@Override public int getIconWidth() { return SIZE; }
		@Override public int getIconHeight() { return SIZE; }
	}
	
	/**
	 * Saves the current state of the UI controls as a binding for the selected key.
	 * This method is called whenever a relevant control is changed.
	 */
	private void saveBindings() {
		if (key == null || loadingData) {
			return; // Do nothing if no key is selected or if data is being loaded.
		}
		
		String prop = "G" + key.getG13KeyCode();
		if (passthroughButton.isSelected()) {
			// Format the passthrough binding string and save it.
			String val = "p,k." + passthroughCode;
			bindings.put(prop, val);
			
			// Update the key's display properties for the ImageMap.
			key.setMappedValue(passthroughText.getText().trim());
			key.setRepeats("N/A");
		} else if (macroButton.isSelected()) {
			// Format the macro binding string and save it.
			int macroNum = macroSelectionBox.getSelectedIndex();
			int repeats = repeatsCheckBox.isSelected() ? 1 : 0;
			String val = "m," + macroNum + "," + repeats;
			bindings.put(prop, val);
			
			// Update the key's display properties.
			if (macros != null && macroNum >= 0 && macroNum < macros.length) {
				final String macroName = macros[macroNum].getProperty("name", "Unnamed Macro");
				key.setMappedValue("Macro: " + macroName);
				key.setRepeats(repeats == 1 ? "Yes" : "No");
			}
		} else {
			// If neither button is selected, the key is unassigned.
			bindings.remove(prop);
			key.setMappedValue("Unassigned");
			key.setRepeats("N/A");
		}
		
		// Persist the changes to the properties file.
		try {
			Configs.saveBindings(bindingsId, bindings);
		} catch (IOException e) {
			e.printStackTrace();
			JOptionPane.showMessageDialog(this, "Can't Save Bindings: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
		}
	}
}