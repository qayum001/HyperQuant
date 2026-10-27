package com.hyperquant.agentbridge.body;

import com.hyperquant.agentbridge.agent.AgentPlayer;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** The body's repertoire. The harness keeps the matching descriptions it shows to the brain. */
public final class Actions {
    private static final Map<String, BiFunction<AgentPlayer, Args, Action>> FACTORIES = new LinkedHashMap<>();

    static {
        FACTORIES.put("move_to", MovementActions.MoveTo::new);
        FACTORIES.put("collect_items", MovementActions.CollectItems::new);
        FACTORIES.put("look", MovementActions.Look::new);
        FACTORIES.put("wait", MovementActions.Wait::new);
        FACTORIES.put("mine", BlockActions.Mine::new);
        FACTORIES.put("place", BlockActions.Place::new);
        FACTORIES.put("use_block", BlockActions.UseBlock::new);
        FACTORIES.put("use_item", ItemActions.UseItem::new);
        FACTORIES.put("equip", ItemActions.Equip::new);
        FACTORIES.put("select_slot", ItemActions.SelectSlot::new);
        FACTORIES.put("drop", ItemActions.Drop::new);
        FACTORIES.put("attack", EntityActions.Attack::new);
        FACTORIES.put("interact_entity", EntityActions.Interact::new);
        FACTORIES.put("container_click", ContainerActions.Click::new);
        FACTORIES.put("transfer", ContainerActions.Transfer::new);
        FACTORIES.put("quick_move", ContainerActions.QuickMove::new);
        FACTORIES.put("close_container", ContainerActions.Close::new);
        FACTORIES.put("craft", ContainerActions.Craft::new);
        FACTORIES.put("chat", ChatAction::new);
    }

    private Actions() {}

    /** Builds an action; bad names or arguments throw {@link IllegalArgumentException}. Server thread only. */
    public static Action create(AgentPlayer agent, String name, Args args) {
        BiFunction<AgentPlayer, Args, Action> factory = FACTORIES.get(name);
        if (factory == null) {
            throw new IllegalArgumentException("unknown action '" + name + "'; known actions: " + names());
        }
        return factory.apply(agent, args);
    }

    public static List<String> names() {
        return Collections.unmodifiableList(Arrays.asList(FACTORIES.keySet().toArray(new String[0])));
    }
}
