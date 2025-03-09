package com.wooting.control;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.ptr.FloatByReference;
import com.sun.jna.ptr.ShortByReference;

public class WootingControls implements ModInitializer {
	// Scan codes for W, A, S, D keys
	private static final short SCAN_CODE_W = 26;
	private static final short SCAN_CODE_A = 4;
	private static final short SCAN_CODE_S = 22;
	private static final short SCAN_CODE_D = 7;

	private static final float Exp = 3.0f;

	private WootingAnalogSDK sdk;
	private boolean initialized = false;
	private WootingAnalogInput analogInput;

	// Current analog values for debugging
	private float currentWValue = 0;
	private float currentAValue = 0;
	private float currentSValue = 0;
	private float currentDValue = 0;

	// Variables to track last pressed keys for SOCD handling
	private long lastWPressTime = 0;
	private long lastSPressTime = 0;
	private long lastAPressTime = 0;
	private long lastDPressTime = 0;

	// Threshold for detecting key presses (to avoid noise)
	private static final float KEY_DETECT_THRESHOLD = 0.01f;

	// Threshold for actual movement (30%)
	private static final float KEY_ACTIVATION_THRESHOLD = 0.01f;
	private static final float KEY_SOCD_THRESHOLD = 0.30f;

	// Previous frame's key states
	private boolean wWasPressed = false;
	private boolean sWasPressed = false;
	private boolean aWasPressed = false;
	private boolean dWasPressed = false;

	public static float taper(float input, float exponent) {
		// Ensure input is within the range [0.0, 1.0]
		input = Math.min(1.0f, Math.max(0.0f, input));

		// Apply the tapering function
		return (float) Math.pow(input, exponent);
	}

	@Override
	public void onInitialize() {
		System.out.println("Initializing WootingControls mod with Last Input SOCD handling");

		try {
			sdk = Native.load("C:\\Program Files\\wooting-analog-sdk\\wooting_analog_sdk.dll", WootingAnalogSDK.class);
			int result = sdk.wooting_analog_initialise();

			if (result < 0) {
				System.err.println("Failed to initialize Wooting Analog SDK with error code: " + result);
				return;
			}

			initialized = true;
			System.out.println("Wooting Analog SDK initialized successfully");

			// Register client tick event to handle input replacement
			registerEvents();

		} catch (Exception e) {
			System.err.println("Failed to load Wooting Analog SDK: " + e.getMessage());
			e.printStackTrace();
		}
	}

	private void registerEvents() {
		// Register client tick to replace the input handler
		ClientTickEvents.START_CLIENT_TICK.register(client -> {
			if (!initialized || client.player == null) return;

			// Create the analog input if needed
			if (analogInput == null) {
				analogInput = new WootingAnalogInput();
				System.out.println("Created WootingAnalogInput instance");
			}

			// Check if player is in a menu (chat, inventory, etc.) or if the window is not focused
			boolean playerInMenu = client.currentScreen != null;
			boolean windowFocused = client.isWindowFocused();

			// Switch between input handlers based on game state
			if (playerInMenu || !windowFocused) {
				// Only switch if we're currently using analog input
				if (client.player.input instanceof WootingAnalogInput) {
					// Always create a fresh KeyboardInput with current options to avoid NPE
					client.player.input = new KeyboardInput(client.options);
				}
			} else {
				// Use our custom analog input
				if (!(client.player.input instanceof WootingAnalogInput)) {
					client.player.input = analogInput;
				}

				// Read current key values
				float wValue = sdk.wooting_analog_read_analog(SCAN_CODE_W);
				float aValue = sdk.wooting_analog_read_analog(SCAN_CODE_A);
				float sValue = sdk.wooting_analog_read_analog(SCAN_CODE_S);
				float dValue = sdk.wooting_analog_read_analog(SCAN_CODE_D);

				// Store for debugging
				currentWValue = wValue;
				currentAValue = aValue;
				currentSValue = sValue;
				currentDValue = dValue;

				// Check for new key presses and update last press times
				long currentTime = System.currentTimeMillis();

				// W key tracking - use the detection threshold for timestamp tracking
				boolean wIsPressed = wValue > KEY_DETECT_THRESHOLD;
				if (wIsPressed && !wWasPressed) {
					lastWPressTime = currentTime;
				}
				wWasPressed = wIsPressed;

				// S key tracking
				boolean sIsPressed = sValue > KEY_DETECT_THRESHOLD;
				if (sIsPressed && !sWasPressed) {
					lastSPressTime = currentTime;
				}
				sWasPressed = sIsPressed;

				// A key tracking
				boolean aIsPressed = aValue > KEY_DETECT_THRESHOLD;
				if (aIsPressed && !aWasPressed) {
					lastAPressTime = currentTime;
				}
				aWasPressed = aIsPressed;

				// D key tracking
				boolean dIsPressed = dValue > KEY_DETECT_THRESHOLD;
				if (dIsPressed && !dWasPressed) {
					lastDPressTime = currentTime;
				}
				dWasPressed = dIsPressed;
			}
		});
	}

	// Custom Input class that handles analog values from Wooting
	private class WootingAnalogInput extends Input {
		public WootingAnalogInput() {
			// Initialize the base Input class
			super();
		}

		@Override
		public void tick(boolean slowDown, float slowDownAmount) {
			MinecraftClient client = MinecraftClient.getInstance();

			// Double-check if player is in a menu or window not focused
			boolean playerInMenu = client.currentScreen != null;
			boolean windowFocused = client.isWindowFocused();

			if (playerInMenu || !windowFocused) {
				// Reset all movement values if in menu or not focused
				movementForward = 0;
				movementSideways = 0;
				jumping = false;
				sneaking = false;
				return;
			}

			// Read analog values for W, A, S, D keys
			float wValue = sdk.wooting_analog_read_analog(SCAN_CODE_W);
			float aValue = sdk.wooting_analog_read_analog(SCAN_CODE_A);
			float sValue = sdk.wooting_analog_read_analog(SCAN_CODE_S);
			float dValue = sdk.wooting_analog_read_analog(SCAN_CODE_D);

			// Set jump and sneak states based on key presses
			jumping = client.options.jumpKey.isPressed();
			sneaking = client.options.sneakKey.isPressed();

			// Handle regular keyboard input for non-WASD controls
			boolean pressingForward = client.options.forwardKey.isPressed();
			boolean pressingBack = client.options.backKey.isPressed();
			boolean pressingLeft = client.options.leftKey.isPressed();
			boolean pressingRight = client.options.rightKey.isPressed();

			// Apply forward/backward movement (W/S) with SOCD Last Input Priority
			// Use detection threshold for SOCD logic but activation threshold for movement
			boolean wDetected = wValue > KEY_DETECT_THRESHOLD;
			boolean sDetected = sValue > KEY_DETECT_THRESHOLD;

			// Check if keys meet the activation threshold
			boolean wActive = wValue > KEY_ACTIVATION_THRESHOLD;
			boolean sActive = sValue > KEY_ACTIVATION_THRESHOLD;

			if (wValue > KEY_SOCD_THRESHOLD && sValue > KEY_SOCD_THRESHOLD) {
				// Both keys detected - use last input priority
				if (lastWPressTime > lastSPressTime) {
					// W was pressed more recently
					movementForward = wActive ? taper(wValue,Exp) : 0;
				} else {
					// S was pressed more recently
					movementForward = sActive ? -taper(sValue,Exp) : 0;
				}
			} else if (wActive) {
				movementForward = taper(wValue,Exp);
			} else if (sActive) {
				movementForward = -taper(sValue,Exp);
			} else if (pressingForward) {
				movementForward = 1.0f;
			} else if (pressingBack) {
				movementForward = -1.0f;
			} else {
				movementForward = 0;
			}

			// Apply left/right movement (A/D) with SOCD Last Input Priority
			boolean aDetected = aValue > KEY_DETECT_THRESHOLD;
			boolean dDetected = dValue > KEY_DETECT_THRESHOLD;

			// Check if keys meet the activation threshold
			boolean aActive = aValue > KEY_ACTIVATION_THRESHOLD;
			boolean dActive = dValue > KEY_ACTIVATION_THRESHOLD;

			if (aValue > KEY_SOCD_THRESHOLD && dValue > KEY_SOCD_THRESHOLD) {
				// Both A and D keys are detected - use last input priority for SOCD
				if (lastAPressTime > lastDPressTime) {
					// A was pressed more recently, move left (A is negative)
					movementSideways = aActive ? taper(aValue,Exp) : 0;  // A is negative (left)
				} else {
					// D was pressed more recently, move right (D is positive)
					movementSideways = dActive ? -taper(dValue,Exp) : 0;   // D is positive (right)
				}
			} else if (aActive) {
				// Only A is active, move left (A is negative)
				movementSideways = taper(aValue,Exp);  // A is negative (left)
			} else if (dActive) {
				// Only D is active, move right (D is positive)
				movementSideways = -taper(dValue,Exp);   // D is positive (right)
			} else if (pressingLeft) {
				// Handle pressing left through the default keyboard input
				movementSideways = -1.0f;  // Default left movement (negative)
			} else if (pressingRight) {
				// Handle pressing right through the default keyboard input
				movementSideways = 1.0f;   // Default right movement (positive)
			} else {
				// Neither A nor D is pressed, no movement
				movementSideways = 0;
			}




			// Apply slow down if requested (e.g., when sneaking)
			if (slowDown) {
				movementForward *= slowDownAmount;
				movementSideways *= slowDownAmount;
			}
		}
	}

	// Interface for the Wooting Analog SDK
	private interface WootingAnalogSDK extends Library {
		int wooting_analog_initialise();
		float wooting_analog_read_analog(short code);
		int wooting_analog_read_full_buffer(ShortByReference code_buffer, FloatByReference analog_buffer, int buffer_size);
	}
}