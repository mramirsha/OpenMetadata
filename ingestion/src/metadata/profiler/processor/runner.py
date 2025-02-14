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
Module in charge of running the queries against
the session.

This is useful to centralise the running logic
and manage behavior such as timeouts.
"""
from typing import Dict, Optional, Union

from sqlalchemy import text
from sqlalchemy.orm import DeclarativeMeta, Query, Session
from sqlalchemy.orm.util import AliasedClass

from metadata.profiler.processor.handle_partition import (
    build_partition_predicate,
    partition_filter_handler,
)
from metadata.utils.logger import query_runner_logger
from metadata.utils.sqa_utils import get_query_filter_for_runner

logger = query_runner_logger()


class QueryRunner:
    """
    Handles the query runs and returns the results
    to the caller.

    The goal of this class is abstract a bit
    how to get the query results. Moreover,
    we can then wrap it up with a timeout
    to make sure that methods executed from this class
    won't take more than X seconds to execute.
    """

    def __init__(
        self,
        session: Session,
        table: DeclarativeMeta,
        sample: Union[DeclarativeMeta, AliasedClass],
        partition_details: Optional[Dict] = None,
        profile_sample_query: Optional[str] = None,
    ):
        self._session = session
        self.table = table
        self._sample = sample
        self.partition_details = partition_details
        self.profile_sample_query = profile_sample_query

    def _build_query(self, *entities, **kwargs) -> Query:
        return self._session.query(*entities, **kwargs)

    def _select_from_sample(self, *entities, **kwargs):
        """Run select statement against sample data"""
        filter_ = get_query_filter_for_runner(kwargs)

        query = self._build_query(*entities, **kwargs).select_from(self._sample)

        if filter_ is not None:
            return query.filter(filter_)

        return query

    def _select_from_user_query(self, *entities, **kwargs):
        """Run select statement against user defined query"""
        filter_ = get_query_filter_for_runner(kwargs)

        user_query = self._session.query(self.table).from_statement(
            text(f"{self.profile_sample_query}")
        )

        query = self._build_query(*entities, **kwargs).select_from(user_query)

        if filter_ is not None:
            return query.filter(filter_)

        return query

    @partition_filter_handler()
    def select_first_from_table(self, *entities, **kwargs):
        """Select first row from the table"""
        filter_ = get_query_filter_for_runner(kwargs)

        if self.profile_sample_query:
            return self._select_from_user_query(*entities, **kwargs).first()
        query = self._build_query(*entities, **kwargs).select_from(self.table)

        if filter_ is not None:
            return query.filter(filter_).first()

        return query.first()

    @partition_filter_handler(first=False)
    def select_all_from_table(self, *entities, **kwargs):
        """Select all rows from the table"""
        filter_ = get_query_filter_for_runner(kwargs)

        if self.profile_sample_query:
            return self._select_from_user_query(*entities, **kwargs).all()

        query = self._build_query(*entities, **kwargs).select_from(self.table)

        if filter_ is not None:
            return query.filter(filter_).all()

        return query.all()

    @partition_filter_handler(sampled=True)
    def select_first_from_sample(self, *entities, **kwargs):
        return self._select_from_sample(*entities, **kwargs).first()

    @partition_filter_handler(first=False, sampled=True)
    def select_all_from_sample(self, *entities, **kwargs):
        return self._select_from_sample(*entities, **kwargs).all()

    def yield_from_sample(self, *entities, **kwargs):
        query = self._select_from_sample(*entities, **kwargs)
        if self.partition_details:
            partition_filter = build_partition_predicate(
                self.partition_details,
                self.table.__table__.c,
            )
            query.filter(partition_filter)

        result = self._session.execute(self._select_from_sample(*entities, **kwargs))
        while True:
            rows = result.fetchmany(1000)
            if not rows:
                break
            yield from rows

    def dispatch_query_select_first(self, *entities, **kwargs):
        """dispatch query to sample or all table"""
        if isinstance(self._sample, AliasedClass):
            return self.select_first_from_sample(*entities, **kwargs)
        return self.select_first_from_table(*entities, **kwargs)

    @staticmethod
    def select_first_from_query(query: Query):
        return query.first()

    @staticmethod
    def select_all_from_query(query: Query):
        return query.all()
