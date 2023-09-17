package ch.epfl.biop.wrappers.deepslice;

/**
 * Abstract class which can be extended by remote or local runners
 */
abstract public class DeepSliceTask {
    protected DeepSliceTaskSettings settings;

    public void setSettings(DeepSliceTaskSettings settings) {
        // Replace backslash with forward slash to avoid xml namespace issues
        settings.input_folder = settings.input_folder.replace("\\", "/");
        if (!settings.input_folder.endsWith("/")) {
            settings.input_folder = settings.input_folder+"/";
        }
        if (settings.output_folder!=null) {
            settings.output_folder = settings.output_folder.replace("\\", "/");
            if (!settings.output_folder.endsWith("/")) {
                settings.output_folder = settings.output_folder+"/";
            }
        }
        this.settings = settings;
    }

    abstract public void run() throws Exception;
}
