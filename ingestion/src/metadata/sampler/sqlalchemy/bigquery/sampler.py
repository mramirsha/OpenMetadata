#  Copyright 2021 Collate
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#  http://www.apache.org/licenses/LICENSE-2.0
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
"""
Helper module to handle data sampling
for the profiler
"""
from typing import Dict, Optional, Union

from sqlalchemy import Column
from sqlalchemy.orm import Query

from metadata.generated.schema.entity.data.table import (
    ProfileSampleType,
    Table,
    TableType,
)
from metadata.generated.schema.entity.services.connections.connectionBasicType import (
    DataStorageConfig,
)
from metadata.generated.schema.entity.services.connections.database.datalakeConnection import (
    DatalakeConnection,
)
from metadata.generated.schema.entity.services.databaseService import DatabaseConnection
from metadata.ingestion.ometa.ometa_api import OpenMetadata
from metadata.profiler.processor.handle_partition import partition_filter_handler
from metadata.sampler.models import SampleConfig
from metadata.sampler.sqlalchemy.sampler import SQASampler
from metadata.utils.constants import SAMPLE_DATA_DEFAULT_COUNT


class BigQuerySampler(SQASampler):
    """
    Generates a sample of the data to not
    run the query in the whole table.
    """

    # pylint: disable=too-many-arguments
    def __init__(
        self,
        service_connection_config: Union[DatabaseConnection, DatalakeConnection],
        ometa_client: OpenMetadata,
        entity: Table,
        sample_config: Optional[SampleConfig] = None,
        partition_details: Optional[Dict] = None,
        sample_query: Optional[str] = None,
        storage_config: DataStorageConfig = None,
        sample_data_count: Optional[int] = SAMPLE_DATA_DEFAULT_COUNT,
        table_type: TableType = None,
        **kwargs,
    ):
        super().__init__(
            service_connection_config=service_connection_config,
            ometa_client=ometa_client,
            entity=entity,
            sample_config=sample_config,
            partition_details=partition_details,
            sample_query=sample_query,
            storage_config=storage_config,
            sample_data_count=sample_data_count,
            **kwargs,
        )
        self.table_type: TableType = table_type

    def _base_sample_query(self, column: Optional[Column], label=None):
        """Base query for sampling

        Args:
            column (Optional[Column]): if computing a column metric only sample for the column
            label (_type_, optional):

        Returns:
        """
        # pylint: disable=import-outside-toplevel
        from sqlalchemy_bigquery import STRUCT

        if column is not None:
            column_parts = column.name.split(".")
            if len(column_parts) > 1:
                # for struct columns (e.g. `foo.bar`) we need to create a new column corresponding to
                # the struct (e.g. `foo`) and then use that in the sample query as the column that
                # will be query is `foo.bar`.
                # e.g. WITH sample AS (SELECT `foo` FROM table) SELECT `foo.bar`
                # FROM sample TABLESAMPLE SYSTEM (n PERCENT)
                column = Column(column_parts[0], STRUCT)
                # pylint: disable=protected-access
                column._set_parent(self.table.__table__)
                # pylint: enable=protected-access

        return super()._base_sample_query(column, label=label)

    @partition_filter_handler(build_sample=True)
    def get_sample_query(self, *, column=None) -> Query:
        """get query for sample data"""
        # TABLESAMPLE SYSTEM is not supported for views
        if (
            self.sample_config.profile_sample_type == ProfileSampleType.PERCENTAGE
            and self.table_type != TableType.View
        ):
            return (
                self._base_sample_query(column)
                .suffix_with(
                    f"TABLESAMPLE SYSTEM ({self.sample_config.profile_sample or 100} PERCENT)",
                )
                .cte(f"{self.table.__tablename__}_sample")
            )

        return super().get_sample_query(column=column)
