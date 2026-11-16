#!/bin/bash
# Logs en vivo de los 3 servicios (broker, API y worker)
docker compose logs -f --tail=100
