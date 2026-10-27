import logging
import os

import uvicorn

from .web import create_app

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
uvicorn.run(create_app(), host=os.environ.get("AVITO_SCOUT_HOST", "127.0.0.1"),
            port=int(os.environ.get("AVITO_SCOUT_PORT", "8793")))
