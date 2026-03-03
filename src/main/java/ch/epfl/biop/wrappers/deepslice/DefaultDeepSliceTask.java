package ch.epfl.biop.wrappers.deepslice;
import org.apposed.appose.Appose;
import org.apposed.appose.Environment;
import org.apposed.appose.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * A local runner for DeepSlice
 */
public class DefaultDeepSliceTask extends DeepSliceTask {

    public void run() throws Exception {
        final Environment env = Appose
                                    .pixi()
                                    .channels("conda-forge")
                                    .conda( "appose", "python==3.12", "numpy")
                                    .pypi("DeepSlice==1.2.6")
                                    .name("deepslice-v0")
                                    //.base()
                                    .logDebug() // log problems
                                    .build();

        try (Service python = env.python().init(callImports())) {
            final Map<String, Object> inputs = new HashMap<>();
            inputs.put("model_name", settings.model);
            inputs.put("input_folder", settings.input_folder);
            inputs.put("output_folder", settings.output_folder); // null is fine, Appose passes it as None
            inputs.put("ensemble", settings.ensemble);
            inputs.put("section_numbers", settings.section_numbers);
            inputs.put("propagate_angles", settings.propagate_angles);
            inputs.put("enforce_index_order", settings.enforce_index_order);
            inputs.put("enforce_index_spacing",
                    settings.use_enforce_index_spacing ? settings.enforce_index_spacing : null);

            final Service.Task task = python.task(getScript(), inputs);
            task.listen(evt -> System.out.println(evt.message));
            task.start();
            task.waitFor();

            if (task.status != Service.TaskStatus.COMPLETE) {
                throw new RuntimeException("DeepSlice failed: " + task.error);
            }

            System.out.println("Output written to: " + task.outputs.get("output_path"));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * These imports have to be executed from the main thread because of a numpy limitation
     * @return imports to import in the main thread
     */
    private static String callImports()
    {
        return ""
                + "from DeepSlice import DSModel\n"
                + "from DeepSlice.read_and_write import QuickNII_functions\n"
                + "import numpy\n";
    }

    private static String getScript() {
        return ""
                + "task.update('Loading model...')\n"
                + "model = DSModel(model_name)\n"
                + "\n"
                + "task.update('Running prediction...')\n"
                + "model.predict(input_folder, ensemble, section_numbers)\n"
                + "\n"
                + "if propagate_angles:\n"
                + "    task.update('Propagating angles...')\n"
                + "    model.propagate_angles()\n"
                + "\n"
                + "if enforce_index_order:\n"
                + "    task.update('Enforcing index order...')\n"
                + "    model.enforce_index_order()\n"
                + "\n"
                + "if enforce_index_spacing is not None:\n"
                + "    task.update('Enforcing index spacing...')\n"
                + "    thickness = None if enforce_index_spacing == 'None' else float(enforce_index_spacing)\n"
                + "    model.enforce_index_spacing(section_thickness=thickness)\n"
                + "\n"
                + "task.update('Saving results...')\n"
                + "filename = output_folder if output_folder else input_folder + 'results'\n"
                + "target = model.config['target_volumes'][model.species]['name']\n"
                + "aligner = model.config['DeepSlice_version']['prerelease']\n"
                + "QuickNII_functions.write_QUINT_JSON(\n"
                + "    df=model.predictions, filename=filename, aligner=aligner, target=target\n"
                + ")\n"
                + "\n"
                + "task.outputs['output_path'] = filename\n"
                + "task.update('done.')\n";
    }
}
