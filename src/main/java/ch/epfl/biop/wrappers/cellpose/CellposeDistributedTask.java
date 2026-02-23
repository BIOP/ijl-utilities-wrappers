package ch.epfl.biop.wrappers.cellpose;

import ch.epfl.biop.wrappers.ExecutePythonInConda;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class CellposeDistributedTask {
    private CellposeDistributedTaskSettings settings;

    public void setSettings(CellposeDistributedTaskSettings settings) {
        this.settings = settings;
    }

    public void run() throws Exception {
        if (!DistributedCellposeUtil.ensureScriptIsCopied(settings.envPath)) {
            throw new Exception("Could not copy distributed_cellpose_cli.py to " + settings.envPath);
        }

        List<String> arguments = new ArrayList<>();
        arguments.add(DistributedCellposeUtil.getScriptPath(settings.envPath));

        File input = new File(settings.inputPath);
        if (input.isDirectory()) {
            arguments.add("--input_dir");
        } else {
            arguments.add("--input_file");
        }
        arguments.add(settings.inputPath);

        if (input.isDirectory()) {
            arguments.add("--output_dir");
        } else {
            arguments.add("--output_tif");
        }
        arguments.add(settings.outputPath);

        arguments.add("--model");
        arguments.add(settings.model);

        arguments.add("--diameter");
        arguments.add(String.valueOf(settings.diameter));

        arguments.add("--chan");
        arguments.add(String.valueOf(settings.ch1));

        if (settings.ch2 != -1) {
            arguments.add("--chan2");
            arguments.add(String.valueOf(settings.ch2));
        }

        arguments.add("--blocksize");
        arguments.add(settings.blocksize);

        arguments.add("--n_workers");
        arguments.add(String.valueOf(settings.n_workers));

        arguments.add("--flow_threshold");
        arguments.add(String.valueOf(settings.flow_threshold));

        arguments.add("--cellprob_threshold");
        arguments.add(String.valueOf(settings.cellprob_threshold));

        arguments.add("--stitch_threshold");
        arguments.add(String.valueOf(settings.stitch_threshold));

        arguments.add("--min_size");
        arguments.add(String.valueOf(settings.min_size));

        if (settings.min_intensity != null && !settings.min_intensity.equalsIgnoreCase("None")) {
            arguments.add("--min_intensity");
            arguments.add(settings.min_intensity);
        }

        if (settings.use_gpu) {
            arguments.add("--use_gpu");
        }

        if (settings.optimize_parallel) {
            arguments.add("--optimize_parallel");
        }

        if (settings.show_dashboard) {
            arguments.add("--open_dashboard");
        }

        if (settings.anisotropy != 1.0) {
            arguments.add("--anisotropy");
            arguments.add(String.valueOf(settings.anisotropy));
        }

        if (settings.logDirectory != null) {
            arguments.add("--log_dir");
            arguments.add(settings.logDirectory);
        }

        arguments.add("--batch_size");
        arguments.add(String.valueOf(settings.batch_size));

        if (settings.gauss > 0) {
            arguments.add("--gauss");
            arguments.add("" + settings.gauss);
        }

        if (settings.median > 0) {
            arguments.add("--median");
            arguments.add("" + settings.median);
        }

        if (settings.global_norm) {
            arguments.add("--global_norm");
        }

        if (settings.bsize > 0) {
            arguments.add("--bsize");
            arguments.add("" + settings.bsize);
        }

        if (settings.tile_overlap != 0.1) {
            arguments.add("--tile_overlap");
            arguments.add("" + settings.tile_overlap);
        }

        if (!settings.additional_flags.trim().isEmpty()) {
            String[] flagsList = settings.additional_flags.split(",");
            for (String s : flagsList) {
                String flag = s.trim();
                if (!flag.isEmpty()) {
                    // Automatically add -- if it's missing from a flag-like string
                    if (!flag.startsWith("-")) {
                        flag = "--" + flag;
                    }
                    arguments.add(flag);
                }
            }
        }

        ExecutePythonInConda.execute(settings.envPath, settings.envType, true, arguments, null);
    }
}
