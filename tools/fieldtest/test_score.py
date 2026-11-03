#!/usr/bin/env python3
"""Tests for score.py. Run: python3 -I tools/fieldtest/test_score.py"""

import contextlib
import csv
import importlib.util
import io
import json
import os
import tempfile
import unittest
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("score", os.path.join(HERE, "score.py"))
score = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(score)

# Downtown Chicago; 0.0001 deg latitude is about 11 m.
LAT, LON = 41.8800, -87.6300
T0 = 1_793_000_000_000  # run start, ms


def write_csv(path, fields, rows):
    with open(path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)


def truth_row(id_, dlat, type_="pothole", expect="yes"):
    return {"id": id_, "lat": LAT + dlat, "lon": LON, "type": type_, "expect_hit": expect,
            "severity": "", "notes": ""}


def iso(ms):
    return datetime.fromtimestamp(ms / 1000, timezone.utc).isoformat()


def run_row(i, vehicle="four_wheeler"):
    start = T0 + i * 3_600_000
    return {"run_id": f"R{i + 1}", "start": iso(start), "end": iso(start + 600_000),
            "vehicle_type": vehicle, "phone": "Pixel 8", "mount": "dash", "distance_mi": "2", "notes": ""}


def det(run_index, dlat, delta=6.0, speed_kmh=40.0, type_="pothole", vehicle="four_wheeler", offset_ms=60_000):
    det.n += 1
    return {"event_id": f"e{det.n}", "timestamp_ms": T0 + run_index * 3_600_000 + offset_ms,
            "latitude": LAT + dlat, "longitude": LON, "accuracy_m": 8, "intensity": 0.5,
            "speed_kmh": speed_kmh, "anomaly_type": type_, "vehicle_type": vehicle,
            "accel_delta_z": delta, "gyro_peak_magnitude": 0.8, "session_id": "s",
            "manufacturer": "Google", "model": "Pixel 8"}


det.n = 0


class ScoreTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.truth = [truth_row("P1", 0.0), truth_row("P2", 0.0100), truth_row("F1", 0.0200, "feature", "no"),
                      truth_row("X1", 0.0300, "pothole", "no")]
        self.runs = [run_row(0)]
        self.dets = []

    def score(self, min_speed_mph=9.0):
        p = lambda name: os.path.join(self.dir.name, name)
        write_csv(p("truth.csv"), list(self.truth[0]), self.truth)
        write_csv(p("runs.csv"), list(self.runs[0]), self.runs)
        write_csv(p("dets.csv"), list(det(0, 0)), self.dets)
        args = score.argparse.Namespace(truth=p("truth.csv"), runs=p("runs.csv"), detections=[p("dets.csv")],
                                        out=p("out"), radius=25.0, min_confidence=0.30,
                                        min_speed_mph=min_speed_mph)
        with contextlib.redirect_stdout(io.StringIO()):
            return score.run(args)

    def test_detection_on_defect_is_true_positive(self):
        self.dets = [det(0, 0.00005)]  # ~5.6 m away from P1
        rep = self.score()
        self.assertEqual(rep["summary"]["true_positives"], 1)
        self.assertEqual(rep["summary"]["false_positives"], 0)
        self.assertAlmostEqual(rep["summary"]["recall_per_pass"], 0.5)  # P1 caught, P2 missed
        self.assertAlmostEqual(rep["location_error_m"]["median"], 5.6, delta=0.3)

    def test_detection_far_from_everything_is_false_positive(self):
        self.dets = [det(0, 0.0050)]  # ~550 m from any labelled point
        rep = self.score()
        self.assertEqual(rep["summary"]["false_positives"], 1)
        self.assertEqual(rep["summary"]["precision"], 0.0)
        self.assertEqual(rep["summary"]["fp_per_mile"], 0.5)

    def test_detection_at_feature_is_not_false_positive(self):
        self.dets = [det(0, 0.0200)]
        rep = self.score()
        self.assertEqual(rep["summary"]["known_features"], 1)
        self.assertEqual(rep["summary"]["false_positives"], 0)
        self.assertIsNone(rep["summary"]["precision"])

    def test_defect_outside_wheel_path_counts_for_precision_not_recall(self):
        self.dets = [det(0, 0.0300)]
        rep = self.score()
        self.assertEqual(rep["expected_defects"], 2)
        self.assertEqual(rep["summary"]["true_positives"], 1)
        self.assertEqual(rep["summary"]["recall_per_pass"], 0.0)

    def test_duplicates_on_same_pass(self):
        self.dets = [det(0, 0.0), det(0, 0.00002, offset_ms=61_000)]
        rep = self.score()
        self.assertEqual(rep["summary"]["duplicates"], 1)
        self.assertEqual(rep["per_run"][0]["caught"], 1)

    def test_detection_outside_run_window_is_ignored(self):
        self.dets = [det(0, 0.0, offset_ms=-60_000)]
        rep = self.score()
        self.assertEqual(rep["detections_scored"], 0)
        self.assertTrue(any("outside every run" in n for n in rep["notes"]))

    def test_slow_detections_filtered_unless_disabled(self):
        self.dets = [det(0, 0.0, speed_kmh=8.0)]  # ~5 mph
        self.assertEqual(self.score()["detections_scored"], 0)
        self.assertEqual(self.score(min_speed_mph=0)["detections_scored"], 1)

    def test_three_passes_form_a_map_cluster(self):
        self.runs = [run_row(i) for i in range(3)]
        self.dets = [det(i, 0.00003 * i) for i in range(3)]
        rep = self.score()
        self.assertEqual(rep["map"]["found_ids"], ["P1"])
        self.assertAlmostEqual(rep["map"]["recall"], 0.5)
        self.assertEqual(rep["map"]["precision"], 1.0)

    def test_two_passes_are_not_enough_for_a_cluster(self):
        self.runs = [run_row(i) for i in range(2)]
        self.dets = [det(i, 0.0) for i in range(2)]
        rep = self.score()
        self.assertEqual(rep["map"]["shown"], 0)
        self.assertTrue(any("fewer than 3 passes" in n for n in rep["notes"]))

    def test_sweep_drops_weak_detections(self):
        self.dets = [det(0, 0.0, delta=4.2), det(0, 0.0100, delta=6.5)]
        sweep = {r["offset_ms2"]: r for r in self.score()["sweep"]}
        self.assertEqual(sweep[0.0]["detections"], 2)
        self.assertEqual(sweep[0.5]["detections"], 1)
        self.assertEqual(sweep[2.0]["detections"], 1)
        self.assertEqual(sweep[2.0]["recall_per_pass"], 0.5)

    def test_two_wheeler_sweep_uses_its_own_base_threshold(self):
        self.runs = [run_row(0, "two_wheeler")]
        self.dets = [det(0, 0.0, delta=5.2, vehicle="two_wheeler")]
        sweep = {r["offset_ms2"]: r for r in self.score()["sweep"]}
        self.assertEqual(sweep[0.0]["detections"], 1)
        self.assertEqual(sweep[0.5]["detections"], 0)  # 5.2 < 5.0 + 0.5

    def test_type_confusion(self):
        self.dets = [det(0, 0.0, type_="bump"), det(0, 0.0100, type_="pothole")]
        rep = self.score()
        self.assertEqual(rep["type_confusion"]["pothole"], {"bump": 1, "pothole": 1})
        self.assertEqual(rep["type_accuracy"], 0.5)

    def test_map_html_embeds_data(self):
        self.dets = [det(0, 0.0)]
        self.score()
        with open(os.path.join(self.dir.name, "out", "map.html")) as f:
            html = f.read()
        self.assertNotIn("__DATA__", html)
        blob = html.split("const DATA = ", 1)[1].split(";\nconst map", 1)[0]
        self.assertEqual(len(json.loads(blob)["truth"]), 4)

    def test_bad_truth_type_is_rejected(self):
        self.truth[0]["type"] = "sinkhole"
        with self.assertRaises(score.InputError):
            self.score()

    def test_overlapping_runs_are_rejected(self):
        r2 = run_row(0)
        r2["run_id"] = "R2"
        self.runs.append(r2)
        with self.assertRaises(score.InputError):
            self.score()

    def test_comment_rows_are_skipped(self):
        self.truth.insert(0, {"id": "# a comment", "lat": "", "lon": "", "type": "", "expect_hit": "",
                              "severity": "", "notes": ""})
        self.dets = [det(0, 0.0)]
        self.assertEqual(self.score()["expected_defects"], 2)

    def test_eventbatch_json_input(self):
        path = os.path.join(self.dir.name, "batch.json")
        e = det(0, 0.0)
        e["sensor_summary"] = {"accel_delta_z": e.pop("accel_delta_z")}
        with open(path, "w") as f:
            json.dump({"batch_id": "b", "events": [e]}, f)
        events = score.read_detections([path])
        self.assertEqual(events[0]["accel_delta_z"], 6.0)


if __name__ == "__main__":
    unittest.main()
