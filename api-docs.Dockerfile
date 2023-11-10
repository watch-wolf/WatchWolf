FROM nikolaik/python-nodejs
ENV PYTHONUNBUFFERED=1
WORKDIR /app

RUN pip3 install javascript==1!1.0.0
RUN npm install --save bytefield-svg

COPY api-docs.py api-docs.py

CMD [ "python3", "api-docs.py"]