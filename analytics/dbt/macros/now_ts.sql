{# Run time as a timestamp without time zone, comparable with the archive's TIMESTAMP(3) columns. #}
{% macro now_ts() -%}
    {%- if target.type == 'duckdb' -%} cast(current_localtimestamp() as timestamp)
    {%- else -%} localtimestamp
    {%- endif -%}
{%- endmacro %}
