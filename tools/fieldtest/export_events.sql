-- Export detections for a field test as CSV.
--
-- From the repository root, with the backend running in Docker:
--
--   docker compose -f backend/docker-compose.yml exec -T db \
--     psql -U asphalt asphalt -f - < tools/fieldtest/export_events.sql > detections.csv
--
-- Exports the last 14 days. score.py keeps only events inside a run's time window.
\copy (SELECT event_id, timestamp_ms, latitude, longitude, accuracy_m, intensity, speed_kmh, anomaly_type, vehicle_type, accel_delta_z, gyro_peak_magnitude, session_id, manufacturer, model FROM road_events WHERE timestamp_ms > (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint - 14 * 86400000 ORDER BY timestamp_ms) TO STDOUT WITH CSV HEADER
