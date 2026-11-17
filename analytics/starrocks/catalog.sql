-- StarRocks reads the Iceberg lakehouse through the Polaris REST catalog (PDF §5.6). Applied by the starrocks-setup job.
CREATE EXTERNAL CATALOG IF NOT EXISTS iceberg
PROPERTIES (
  "type" = "iceberg",
  "iceberg.catalog.type" = "rest",
  "iceberg.catalog.uri" = "http://polaris.lakehouse.svc:8181/api/catalog",
  "iceberg.catalog.warehouse" = "difme",
  "iceberg.catalog.security" = "oauth2",
  "iceberg.catalog.oauth2.credential" = "${POLARIS_CREDENTIAL}",
  "aws.s3.endpoint" = "http://seaweedfs-s3.lakehouse.svc:8333",
  "aws.s3.enable_path_style_access" = "true",
  "aws.s3.access_key" = "${S3_ACCESS_KEY}",
  "aws.s3.secret_key" = "${S3_SECRET_KEY}"
);
