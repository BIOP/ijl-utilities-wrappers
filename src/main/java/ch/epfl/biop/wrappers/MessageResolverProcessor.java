package ch.epfl.biop.wrappers;

import org.scijava.ItemVisibility;
import org.scijava.log.LogService;
import org.scijava.module.Module;
import org.scijava.module.ModuleItem;
import org.scijava.module.process.AbstractPreprocessorPlugin;
import org.scijava.module.process.PreprocessorPlugin;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.Button;
import org.scijava.widget.InputHarvester;

/**
 * Scijava processor that resolves all inputs with MESSAGE {@link ItemVisibility},
 * if they are the only ones remaining, before the InputHarvester kicks in
 */
@Plugin(type = PreprocessorPlugin.class, priority = InputHarvester.PRIORITY+1) // We want it to kick in before the swing input harvester
public class MessageResolverProcessor extends AbstractPreprocessorPlugin {

    int unresolvedInputsExceptMessageCount = 0;
    int messagesCount = 0;
    int buttonCount = 0;

    @Parameter
    LogService logger;

    @Override
    public void process(Module module) {

        if (module.getInfo()==null) {
            logger.warn("null getInfo for module "+module);
            return;
        }

        module.getInputs().forEach((name, input) -> {
            ModuleItem<?> inputKind = module.getInfo().getInput(name);
            if (inputKind == null) {
                logger.warn("null input "+name+" for module "+module);
                return; // avoid doing anything
            }

            ItemVisibility visibility = inputKind.getVisibility();

            if (visibility==null) {
                logger.warn("null visibility for input "+name+" for module "+module);
                return; // avoid doing anything
            }

            if (visibility.equals(ItemVisibility.MESSAGE)) {
                messagesCount++;
            } else {
                if (!module.isInputResolved(name)) unresolvedInputsExceptMessageCount++;
            }
        });

        module.getInputs().forEach((name, input) -> {
            ModuleItem<?> inputKind = module.getInfo().getInput(name);
            if (inputKind == null) {
                logger.warn("null input "+name+" for module "+module);
                return; // avoid doing anything
            }
            if (inputKind.getType().equals(Button.class)) buttonCount++;
        });

        if (messagesCount > 0) {
            if (unresolvedInputsExceptMessageCount == buttonCount) {
                // No need for null check, it's been done before
                module.getInputs().forEach((name, input) -> {
                    if (module.getInfo().getInput(name).getVisibility().equals(ItemVisibility.MESSAGE) || module.getInfo().getInput(name).getType().equals(Button.class)) {
                        logger.debug("Resolving parameter "+name+" in module "+module+" in MessageResolverProcessor.");
                        module.resolveInput(name);
                    }
                });
            }
        }

    }
}
