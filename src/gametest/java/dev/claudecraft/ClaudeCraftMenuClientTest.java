package dev.claudecraft;

import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.claudecraft.client.ClaudeCraftScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.PauseScreen;

import java.nio.file.Files;
import java.nio.file.Path;

/** The host opens the ClaudeCraft menu from the pause menu and changes how many sessions run at once. */
public class ClaudeCraftMenuClientTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		Path cfgFile = FabricLoader.getInstance().getConfigDir().resolve(ClaudeCraft.MOD_ID).resolve("config.json");
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			int before = sp.getServer().computeOnServer(s -> sessions());
			check(before == 2, "the default is two sessions: " + ClaudeCraft.bridge().status());

			ctx.setScreen(() -> new PauseScreen(true));
			check(ctx.tryClickScreenButton("ClaudeCraft…"), "the pause menu has a ClaudeCraft button");
			ctx.waitForScreen(ClaudeCraftScreen.class);
			ctx.waitFor(mc -> ClaudeCraftScreen.loaded(), 100);
			ctx.waitTick();
			check(!ctx.computeOnClient(mc -> button(mc, "Use my own Claude").active), "the host can't switch to their own Claude");
			check(ctx.computeOnClient(mc -> button(mc, "Claude sessions at once").active), "the host can change the world's settings");
			ctx.takeScreenshot("claudecraft-menu");

			check(press(ctx, "Claude sessions at once: 2"), "the sessions button shows 2");
			sp.getServer().waitFor(s -> sessions() == 3, 100);
			check(JsonParser.parseString(Files.readString(cfgFile)).getAsJsonObject().get("maxConcurrentSessions").getAsInt() == 3,
				"the new limit is saved");
			ctx.waitFor(mc -> button(mc, "Claude sessions at once").getMessage().getString().endsWith(": 3"), 100);
			Config.saveSetting(cfgFile, "maxConcurrentSessions", new JsonPrimitive(2));
			ctx.setScreen(() -> null);
		} catch (java.io.IOException e) {
			throw new RuntimeException(e);
		}
	}

	/** Press the button whose text is exactly this, like tryClickScreenButton but for any button (it only presses plain Buttons). */
	static boolean press(ClientGameTestContext ctx, String text) {
		return ctx.computeOnClient(mc -> {
			for (var c : mc.gui.screen().children()) {
				if (c instanceof net.minecraft.client.gui.components.AbstractButton b && b.active && b.getMessage().getString().equals(text)) {
					b.onPress(new net.minecraft.client.input.MouseButtonInfo(0, 0));
					return true;
				}
			}
			return false;
		});
	}

	/** How many of the host's sessions the bridge has, from /claude status. */
	private static int sessions() {
		String status = ClaudeCraft.bridge().status();
		int n = 0;
		while (status.contains("#" + (n + 1) + ": ")) n++;
		return Math.max(n, 1);
	}

	private static AbstractWidget button(net.minecraft.client.Minecraft mc, String label) {
		for (var child : mc.gui.screen().children()) {
			if (child instanceof AbstractWidget w && w.getMessage().getString().startsWith(label)) return w;
		}
		throw new AssertionError("No " + label + " button");
	}

	private static void check(boolean ok, String what) {
		if (!ok) throw new AssertionError(what);
	}
}
