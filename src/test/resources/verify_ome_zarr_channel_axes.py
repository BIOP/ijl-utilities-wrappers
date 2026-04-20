#!/usr/bin/env python

import importlib.util
import pathlib
import shutil
import tempfile

import numpy as np
import zarr


ROOT = pathlib.Path(__file__).resolve().parents[2]
CONVERTER_PATH = ROOT / "main" / "resources" / "convert_to_zarr_for_cellpose.py"
RUNNER_PATH = ROOT / "main" / "resources" / "distributed_cellpose_run.py"


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def assert_equal(actual, expected, label):
    if actual != expected:
        raise AssertionError(f"{label}: expected {expected!r}, got {actual!r}")


class Args:
    channel_axis = -1
    ch1 = 0
    ch2 = -1


def main():
    converter = load_module("converter", CONVERTER_PATH)
    runner = load_module("runner", RUNNER_PATH)

    temp_dir = pathlib.Path(tempfile.mkdtemp(prefix="ome_zarr_axes_"))
    try:
        one_channel_path = temp_dir / "one.ome.zarr"
        one_channel_data = np.arange(1 * 4 * 5 * 6, dtype=np.uint16).reshape(1, 4, 5, 6)
        converter._write_ome_zarr(
            str(one_channel_path),
            one_channel_data,
            (1, 2, 5, 6),
            {"Z": 1.0, "Y": 1.0, "X": 1.0},
            1,
            "one",
        )

        one_root = zarr.open_group(str(one_channel_path), mode="r")
        one_info = runner.build_level_infos(one_root, {"Z": 1.0, "Y": 1.0, "X": 1.0})[0]
        one_args = Args()
        one_plan = runner.build_channel_plan(one_info.array, one_args, axes_names=one_info.axes_names)

        assert_equal(tuple(one_root["0"].shape), (1, 4, 5, 6), "one-channel stored shape")
        assert_equal(tuple(one_info.axes_names), ("c", "z", "y", "x"), "one-channel axes")
        assert_equal(one_plan.source_channel_axis, 0, "one-channel detected axis")
        assert_equal(one_plan.source_channel_count, 1, "one-channel source count")
        assert_equal(one_plan.processed_channel_count, 1, "one-channel processed count")
        assert_equal(tuple(one_plan.input_zarr.shape), (4, 5, 6), "one-channel adapter shape")

        two_channel_path = temp_dir / "two.ome.zarr"
        two_channel_data = np.arange(2 * 4 * 5 * 6, dtype=np.uint16).reshape(2, 4, 5, 6)
        converter._write_ome_zarr(
            str(two_channel_path),
            two_channel_data,
            (2, 2, 5, 6),
            {"Z": 1.0, "Y": 1.0, "X": 1.0},
            1,
            "two",
        )

        two_root = zarr.open_group(str(two_channel_path), mode="r")
        two_info = runner.build_level_infos(two_root, {"Z": 1.0, "Y": 1.0, "X": 1.0})[0]
        two_args = Args()
        two_args.ch2 = 1
        two_plan = runner.build_channel_plan(two_info.array, two_args, axes_names=two_info.axes_names)

        assert_equal(tuple(two_root["0"].shape), (2, 4, 5, 6), "two-channel stored shape")
        assert_equal(tuple(two_info.axes_names), ("c", "z", "y", "x"), "two-channel axes")
        assert_equal(two_plan.source_channel_axis, 0, "two-channel detected axis")
        assert_equal(two_plan.source_channel_count, 2, "two-channel source count")
        assert_equal(two_plan.processed_channel_count, 2, "two-channel processed count")
        assert_equal(tuple(two_plan.input_zarr.shape), (4, 5, 6), "two-channel adapter shape")

        print("OME-Zarr channel axis smoke test passed")
    finally:
        shutil.rmtree(temp_dir, ignore_errors=True)


if __name__ == "__main__":
    main()